// 鸿蒙端录屏 + H.264 编码：OH_AVScreenCapture（surface 模式）直接画进 OH_VideoEncoder 的输入 surface（零拷贝），
// 编码输出经线程安全函数交给调用 start 的 ArkTS 线程（Worker）加密发送。
// 所有回调都只往 ArkTS 投递事件，停止和释放只在 ArkTS 调 stop 时做（文档要求不能在回调里 Stop / Release）。
#include <napi/native_api.h>
#include <multimedia/player_framework/native_avbuffer.h>
#include <multimedia/player_framework/native_avcodec_base.h>
#include <multimedia/player_framework/native_avcodec_videoencoder.h>
#include <multimedia/player_framework/native_avformat.h>
#include <multimedia/player_framework/native_avscreen_capture.h>
#include <multimedia/player_framework/native_avscreen_capture_base.h>
#include <multimedia/player_framework/native_avscreen_capture_errors.h>
#include <native_window/external_window.h>
#include <atomic>
#include <cstring>
#include <vector>

namespace {

// 给 ArkTS 的事件，和 types/libcast/index.d.ts 一致
enum Kind { CONFIG = 0, FRAME = 1, STATE = 2, ERROR = 3, GAP = 4 };

struct Event {
    int kind;
    int value; // FRAME：1 是关键帧；STATE：OH_AVScreenCaptureStateCode；ERROR：错误码
    int64_t pts;
    std::vector<uint8_t> data;
};

// 一个 App 同时只录一路
struct {
    OH_AVScreenCapture *cap = nullptr;
    OH_AVCodec *enc = nullptr;
    OHNativeWindow *win = nullptr;
    napi_threadsafe_function tsfn = nullptr;
    std::vector<uint8_t> csd;          // 上次交出去的 SPS/PPS，只在编码输出线程用
    std::atomic<int> queued{0};        // 已投递、ArkTS 还没处理的视频帧
    std::atomic<bool> dropping{false}; // 丢过帧：之后的非关键帧都丢，直到关键帧
} g;

// ArkTS 线程忙（比如 send 阻塞）时不排队视频：积压这么多帧就丢
constexpr int MAX_QUEUED = 4;

void Post(int kind, int value, int64_t pts = 0, std::vector<uint8_t> data = {}) {
    auto *e = new Event{kind, value, pts, std::move(data)};
    if (g.tsfn == nullptr || napi_call_threadsafe_function(g.tsfn, e, napi_tsfn_nonblocking) != napi_ok) {
        if (kind == FRAME) {
            g.queued--;
        }
        delete e;
    }
}

void CallJs(napi_env env, napi_value cb, void *, void *data) {
    auto *e = static_cast<Event *>(data);
    if (e->kind == FRAME) {
        g.queued--;
    }
    if (env != nullptr && cb != nullptr) {
        napi_value args[4];
        void *p = nullptr;
        napi_create_int32(env, e->kind, &args[0]);
        napi_create_int32(env, e->value, &args[1]);
        napi_create_arraybuffer(env, e->data.size(), &p, &args[2]);
        if (p != nullptr && !e->data.empty()) {
            memcpy(p, e->data.data(), e->data.size());
        }
        napi_create_double(env, static_cast<double>(e->pts), &args[3]);
        napi_value undefined;
        napi_get_undefined(env, &undefined);
        napi_call_function(env, undefined, cb, 4, args, nullptr);
    }
    delete e;
}

size_t FindStart(const uint8_t *p, size_t n, size_t i) {
    for (; i + 3 <= n; i++) {
        if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1) {
            return i;
        }
    }
    return n;
}

// 编码器输出整理成每个 NAL 前都是 00 00 00 01 的 Annex B，拆成参数集（SPS/PPS）和其余部分，返回是否有 IDR。
// 文档没写输出一定是 Annex B：开头不是起始码就按 AVCC（4 字节大端长度）转换。
bool Split(const uint8_t *p, size_t n, std::vector<uint8_t> &ps, std::vector<uint8_t> &rest) {
    static const uint8_t sc[4] = {0, 0, 0, 1};
    bool idr = false;
    auto add = [&](const uint8_t *nal, size_t len) {
        if (len == 0) {
            return;
        }
        int type = nal[0] & 0x1f;
        idr = idr || type == 5;
        auto &out = (type == 7 || type == 8) ? ps : rest;
        out.insert(out.end(), sc, sc + 4);
        out.insert(out.end(), nal, nal + len);
    };
    bool annexB = (n >= 3 && p[0] == 0 && p[1] == 0 && p[2] == 1) ||
                  (n >= 4 && p[0] == 0 && p[1] == 0 && p[2] == 0 && p[3] == 1);
    if (annexB) {
        for (size_t s = FindStart(p, n, 0); s < n;) {
            size_t b = s + 3;
            size_t next = FindStart(p, n, b);
            size_t end = next;
            while (end > b && p[end - 1] == 0) {
                end--; // 下一个 4 字节起始码的前导 0
            }
            add(p + b, end - b);
            s = next;
        }
    } else {
        for (size_t i = 0; i + 4 <= n;) {
            size_t len = static_cast<size_t>(p[i]) << 24 | p[i + 1] << 16 | p[i + 2] << 8 | p[i + 3];
            i += 4;
            if (len > n - i) {
                break;
            }
            add(p + i, len);
            i += len;
        }
    }
    return idr;
}

void OnOutput(OH_AVCodec *codec, uint32_t index, OH_AVBuffer *buffer, void *) {
    OH_AVCodecBufferAttr a{};
    if (OH_AVBuffer_GetBufferAttr(buffer, &a) == AV_ERR_OK && a.size > 0 && !(a.flags & AVCODEC_BUFFER_FLAGS_EOS)) {
        std::vector<uint8_t> ps;
        std::vector<uint8_t> rest;
        // CODEC_DATA 一般单独一块；和关键帧在一块（CODEC_DATA|SYNC_FRAME）或只在 IDR 里带也能拆出来
        bool idr = Split(OH_AVBuffer_GetAddr(buffer) + a.offset, a.size, ps, rest);
        if (!ps.empty() && ps != g.csd) {
            g.csd = ps;
            Post(CONFIG, 0, 0, std::move(ps));
        }
        bool key = idr || (a.flags & AVCODEC_BUFFER_FLAGS_SYNC_FRAME);
        if (!key && !rest.empty() && (g.dropping || g.queued >= MAX_QUEUED)) {
            g.dropping = true;
            Post(GAP, 0); // 每丢一帧都告诉 ArkTS：它限速每秒请求一次关键帧，被限速的那次之后还会再要
        } else if (!rest.empty()) {
            g.dropping = false;
            g.queued++;
            Post(FRAME, key ? 1 : 0, a.pts, std::move(rest));
        }
    }
    OH_VideoEncoder_FreeOutputBuffer(codec, index);
}

void OnEncError(OH_AVCodec *, int32_t code, void *) { Post(ERROR, code); }
void OnStreamChanged(OH_AVCodec *, OH_AVFormat *, void *) {}
void OnNeedInput(OH_AVCodec *, uint32_t, OH_AVBuffer *, void *) {} // surface 模式不用
void OnState(OH_AVScreenCapture *, OH_AVScreenCaptureStateCode state, void *) { Post(STATE, state); }
void OnCapError(OH_AVScreenCapture *, int32_t code, void *) { Post(ERROR, code); }

// strict：Baseline（老车机只保证支持 Baseline）+ CBR。设备不接受时退回编码器默认值
OH_AVCodec *MakeEncoder(int32_t w, int32_t h, int32_t fps, int64_t bps, bool strict) {
    OH_AVCodec *enc = OH_VideoEncoder_CreateByMime(OH_AVCODEC_MIMETYPE_VIDEO_AVC);
    if (enc == nullptr) {
        return nullptr;
    }
    OH_AVCodecCallback cb = {OnEncError, OnStreamChanged, OnNeedInput, OnOutput};
    OH_AVFormat *f = OH_AVFormat_Create();
    OH_AVFormat_SetIntValue(f, OH_MD_KEY_WIDTH, w);
    OH_AVFormat_SetIntValue(f, OH_MD_KEY_HEIGHT, h);
    OH_AVFormat_SetIntValue(f, OH_MD_KEY_PIXEL_FORMAT, AV_PIXEL_FORMAT_NV12);
    OH_AVFormat_SetDoubleValue(f, OH_MD_KEY_FRAME_RATE, fps > 0 ? fps : 30);
    OH_AVFormat_SetLongValue(f, OH_MD_KEY_BITRATE, bps > 0 ? bps : 4000000);
    OH_AVFormat_SetIntValue(f, OH_MD_KEY_I_FRAME_INTERVAL, 10000); // 和安卓、iPhone 端一样每 10 秒一个关键帧
    // 画面静止时 surface 不出新帧：每 100ms 重复上一帧（协议要求至少每 100ms 一帧），不限次数
    OH_AVFormat_SetIntValue(f, OH_MD_KEY_VIDEO_ENCODER_REPEAT_PREVIOUS_FRAME_AFTER, 100);
    OH_AVFormat_SetIntValue(f, OH_MD_KEY_VIDEO_ENCODER_REPEAT_PREVIOUS_MAX_COUNT, -1);
    if (strict) {
        OH_AVFormat_SetIntValue(f, OH_MD_KEY_PROFILE, AVC_PROFILE_BASELINE);
        OH_AVFormat_SetIntValue(f, OH_MD_KEY_VIDEO_ENCODE_BITRATE_MODE, BITRATE_MODE_CBR);
    }
    bool ok = OH_VideoEncoder_RegisterCallback(enc, cb, nullptr) == AV_ERR_OK &&
              OH_VideoEncoder_Configure(enc, f) == AV_ERR_OK;
    OH_AVFormat_Destroy(f);
    if (!ok) {
        OH_VideoEncoder_Destroy(enc);
        return nullptr;
    }
    return enc;
}

void Close() {
    if (g.cap != nullptr) {
        OH_AVScreenCapture_StopScreenCapture(g.cap);
        OH_AVScreenCapture_Release(g.cap);
        g.cap = nullptr;
    }
    if (g.enc != nullptr) {
        OH_VideoEncoder_Stop(g.enc);
        OH_VideoEncoder_Destroy(g.enc);
        g.enc = nullptr;
    }
    if (g.win != nullptr) {
        OH_NativeWindow_DestroyNativeWindow(g.win);
        g.win = nullptr;
    }
    if (g.tsfn != nullptr) {
        // 回调都已停止。还在队列里的事件照常交给 ArkTS，ArkTS 按代数丢弃
        napi_release_threadsafe_function(g.tsfn, napi_tsfn_release);
        g.tsfn = nullptr;
    }
}

// 返回 0 表示已发起（结果看 STATE：STARTED 或 CANCELED），其他是错误码（负数是这里的步骤）
int32_t Open(napi_env env, napi_value cb, int32_t w, int32_t h, int32_t fps, int64_t bps) {
    napi_value name;
    napi_create_string_utf8(env, "drivecast", NAPI_AUTO_LENGTH, &name);
    if (napi_create_threadsafe_function(env, cb, nullptr, name, 0, 1, nullptr, nullptr, nullptr, CallJs, &g.tsfn) !=
        napi_ok) {
        g.tsfn = nullptr;
        return -1;
    }
    g.csd.clear();
    g.queued = 0;
    g.dropping = false;
    g.enc = MakeEncoder(w, h, fps, bps, true);
    if (g.enc == nullptr) {
        g.enc = MakeEncoder(w, h, fps, bps, false); // ponytail: 不是 Baseline 时老车机可能解不了，真机上再看
    }
    if (g.enc == nullptr) {
        return -2;
    }
    if (OH_VideoEncoder_GetSurface(g.enc, &g.win) != AV_ERR_OK || OH_VideoEncoder_Prepare(g.enc) != AV_ERR_OK ||
        OH_VideoEncoder_Start(g.enc) != AV_ERR_OK) {
        return -3;
    }
    g.cap = OH_AVScreenCapture_Create();
    if (g.cap == nullptr) {
        return -4;
    }
    OH_AVScreenCaptureConfig cfg;
    memset(&cfg, 0, sizeof(cfg)); // 文档提醒成员不会自动初始化；音频采样率、声道都是 0 表示不录音频
    cfg.captureMode = OH_CAPTURE_HOME_SCREEN;
    cfg.dataType = OH_ORIGINAL_STREAM;
    cfg.videoInfo.videoCapInfo.videoFrameWidth = w; // 虚拟屏就是车机大小，系统等比缩放（两侧补黑）
    cfg.videoInfo.videoCapInfo.videoFrameHeight = h;
    cfg.videoInfo.videoCapInfo.videoSource = OH_VIDEO_SOURCE_SURFACE_RGBA;
    OH_AVScreenCapture_SetMicrophoneEnabled(g.cap, false);
    OH_AVScreenCapture_SetStateCallback(g.cap, OnState, nullptr);
    OH_AVScreenCapture_SetErrorCallback(g.cap, OnCapError, nullptr);
    OH_AVScreenCapture_SetCanvasRotation(g.cap, true); // 手机转横屏时画面保持正向
    OH_AVScreenCapture_CaptureStrategy *st = OH_AVScreenCapture_CreateCaptureStrategy();
    if (st != nullptr) {
        OH_AVScreenCapture_StrategyForPrivacyMaskMode(st, 1);       // 只遮隐私窗口，不是整屏变黑
        OH_AVScreenCapture_StrategyForKeepCaptureDuringCall(st, true); // 蓝牙通话时继续投屏
        OH_AVScreenCapture_StrategyForFillMode(st, OH_SCREENCAPTURE_FILLMODE_ASPECT_SCALE_FIT);
        OH_AVScreenCapture_SetCaptureStrategy(g.cap, st);
        OH_AVScreenCapture_ReleaseCaptureStrategy(st);
    }
    int32_t err = OH_AVScreenCapture_Init(g.cap, cfg);
    if (err == AV_SCREEN_CAPTURE_ERR_OK) {
        err = OH_AVScreenCapture_StartScreenCaptureWithSurface(g.cap, g.win); // 系统弹隐私确认框
    }
    return err;
}

bool Args(napi_env env, napi_callback_info info, size_t want, napi_value *argv) {
    size_t argc = want;
    return napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr) == napi_ok && argc == want;
}

// start(width, height, fps, bitrate, callback): number
napi_value Start(napi_env env, napi_callback_info info) {
    napi_value argv[5];
    int32_t w = 0;
    int32_t h = 0;
    int32_t fps = 0;
    int64_t bps = 0;
    int32_t err = -5;
    if (Args(env, info, 5, argv) && napi_get_value_int32(env, argv[0], &w) == napi_ok &&
        napi_get_value_int32(env, argv[1], &h) == napi_ok && napi_get_value_int32(env, argv[2], &fps) == napi_ok &&
        napi_get_value_int64(env, argv[3], &bps) == napi_ok && w > 0 && h > 0) {
        Close(); // 上一路没停也先停掉
        err = Open(env, argv[4], w, h, fps, bps);
        if (err != 0) {
            Close();
        }
    }
    napi_value r;
    napi_create_int32(env, err, &r);
    return r;
}

napi_value RequestKeyframe(napi_env env, napi_callback_info) {
    if (g.enc != nullptr) {
        OH_AVFormat *f = OH_AVFormat_Create();
        OH_AVFormat_SetIntValue(f, OH_MD_KEY_REQUEST_I_FRAME, 1);
        OH_VideoEncoder_SetParameter(g.enc, f);
        OH_AVFormat_Destroy(f);
    }
    return nullptr;
}

// setMaxFps(fps)：系统最高 60 帧，按车机要的限。录屏开始（STATE 0）之后才能设，之前设会失败
napi_value SetMaxFps(napi_env env, napi_callback_info info) {
    napi_value argv[1];
    int32_t fps = 0;
    if (g.cap != nullptr && Args(env, info, 1, argv) && napi_get_value_int32(env, argv[0], &fps) == napi_ok) {
        OH_AVScreenCapture_SetMaxVideoFrameRate(g.cap, fps > 0 ? fps : 30);
    }
    return nullptr;
}

napi_value Stop(napi_env env, napi_callback_info) {
    Close();
    return nullptr;
}

napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor d[] = {
        {"start", nullptr, Start, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"requestKeyframe", nullptr, RequestKeyframe, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"setMaxFps", nullptr, SetMaxFps, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"stop", nullptr, Stop, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(d) / sizeof(d[0]), d);
    return exports;
}

napi_module g_module = {1, 0, nullptr, Init, "cast", nullptr, {0}};

} // namespace

extern "C" __attribute__((constructor)) void RegisterCast() { napi_module_register(&g_module); }
