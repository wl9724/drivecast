// cast.cpp 的 ArkTS 接口。回调都在调 start 的线程上。
// 回调的 kind：
//   0 CONFIG  data 是 SPS/PPS（Annex B），车机据此（重新）建解码器
//   1 FRAME   data 是一帧 H.264（Annex B），value 1 = 关键帧，ptsUs 是时间戳
//   2 STATE   value 是 OH_AVScreenCaptureStateCode：0 已开始、1 用户拒绝、2 在状态栏停止、3 被其他录屏打断、
//             4 通话停止、8/9 进出隐私场景、10 切换用户
//   3 ERROR   value 是录屏或编码器的错误码
//   4 GAP     来不及处理丢了帧（每丢一帧报一次），要请求关键帧

/** 开始录屏并编码成 width × height 的 H.264。返回 0 表示已发起（系统会弹隐私确认框），其他是错误码。 */
export const start: (width: number, height: number, fps: number, bitrate: number,
  cb: (kind: number, value: number, data: ArrayBuffer, ptsUs: number) => void) => number;
/** 下一帧编成关键帧。 */
export const requestKeyframe: () => void;
/** 录屏帧率上限。STATE 0（已开始）之后才能设。 */
export const setMaxFps: (fps: number) => void;
/** 停止录屏、释放编码器。只在 ArkTS 里调，不在回调里同步调。 */
export const stop: () => void;
