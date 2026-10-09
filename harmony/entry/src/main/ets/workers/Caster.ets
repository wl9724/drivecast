import { MessageEvents, ThreadWorkerGlobalScope, worker } from '@kit.ArkTS';
import cast from 'libcast.so';
import { Conn, gateway, wifiNet } from '../core/Conn';
import { SecureChannel, authenticate, random } from '../core/Crypto';
import {
  CAR_PORT, CarKey, Endpoint, Found, Frame, FromWorker, Hello, MAGIC, MAX_INCOMING, Msg, PHONE_WATCHDOG_MS, ToWorker,
  hex, helloAck, text, toHex, utf8, videoConfig, videoFrame
} from '../core/Proto';

// 投屏 Worker：找到已配对的车机，认证后用加密连接把录屏的 H.264 发过去，断了就退避重连。
// socket 发送和逐帧加密都在这个线程上（send 在内核缓冲满时会阻塞，不能放 UI 线程）。
// 录屏一旦开始就一直开着，车机断开重连也不停：每次开始录屏系统都会弹确认框，开车时没法点。
// 鸿蒙手机不能被反向控制，车机发来的 TOUCH / KEY / LAUNCH 一律忽略。

const port: ThreadWorkerGlobalScope = worker.workerPort;

// cast.cpp 回调的 kind
const CONFIG = 0;
const FRAME = 1;
const STATE = 2;
const ERROR = 3;
const GAP = 4;

class Caster {
  private active: boolean = false;
  private phoneId: Uint8Array = new Uint8Array(0);
  private ltks: Map<string, Uint8Array> = new Map<string, Uint8Array>();
  private last: string[] = [];
  private manual: string = '';
  private found: Found[] = [];
  private conn: Conn | null = null;
  private ch: SecureChannel | null = null; // 认证之后才有
  private streaming: boolean = false; // 这条连接收到 HELLO 之后
  private hello: Hello | null = null; // 正在录屏的参数
  private started: boolean = false; // 用户在系统确认框里允许了录屏
  private gen: number = 0; // 每次开始录屏加一，旧录屏还在队列里的回调作废
  private config: Uint8Array | null = null; // 编码器最近的 SPS/PPS
  private waitKey: boolean = true;
  private keyAt: number = 0;
  private lastHeard: number = 0;
  private backoff: number = 1;
  private rejected: number = 0; // 连续几次在认证阶段收到明文 BYE（内容未认证，可能是冒充车机的人发的）
  private retry: number = -1;
  private watchdog: number = -1;

  handle(m: ToWorker): void {
    if (m.cmd === 'start' && !this.active) {
      this.active = true;
      this.phoneId = hex(m.phoneId ?? '');
      const cars: CarKey[] = m.cars ?? [];
      this.ltks = new Map<string, Uint8Array>();
      cars.forEach((c: CarKey) => this.ltks.set(c.id, hex(c.ltk)));
      const last: string[] = m.last ?? [];
      this.last = last;
      this.manual = m.manual ?? '';
      const found: Found[] = m.found ?? [];
      this.found = found;
      this.backoff = 1;
      this.rejected = 0;
      // 车机每秒发 PING。Wi-Fi 断开时 TCP 自己发现不了，5 秒没消息就当断了（连接和握手也算在内）
      this.watchdog = setInterval(() => {
        const c = this.conn;
        if (c !== null && Date.now() - this.lastHeard > PHONE_WATCHDOG_MS) {
          c.fail('车机没有响应');
        }
      }, 1000);
      // 知道车机尺寸就趁用户还在点按钮时开始录屏（弹确认框），不等连上车机
      let h: Hello | null = null;
      try {
        h = Hello.parse(hex(m.hello ?? ''));
      } catch (e) {
        h = null; // 刚配对完还没存过（这时车机开着、用户在 App 里）或协议版本变了：等车机的 HELLO 再录屏
      }
      if (h !== null) {
        this.startCapture(h);
      }
      this.connect();
    } else if (m.cmd === 'found') {
      const found: Found[] = m.found ?? [];
      this.found = found;
    } else if (m.cmd === 'stop' && this.active) {
      this.finish('已停止投屏');
    } else if (m.cmd === 'stop') {
      this.post('stopped', '已停止投屏'); // 已经停了也回一声，UI 才能复位
    }
  }

  /** 找车的顺序：mDNS（TXT id 是已配对的车机）→ 上次连上的地址 → Wi-Fi 网关（连车机热点时就是车机）→ 手动地址。 */
  private connect(): void {
    if (!this.active || this.conn !== null) {
      return;
    }
    const list: Endpoint[] = [];
    this.found.filter((f: Found) => this.ltks.has(f.carId)).forEach((f: Found) => list.push(new Endpoint(f.host, f.port)));
    const add = (ep: Endpoint | null): void => {
      if (ep !== null && !list.some((e: Endpoint) => e.toString() === ep.toString())) {
        list.push(ep);
      }
    };
    this.last.forEach((a: string) => add(Endpoint.parse(a)));
    const gw = gateway(wifiNet());
    add(gw.length > 0 ? new Endpoint(gw, CAR_PORT) : null);
    add(Endpoint.parse(this.manual));
    if (list.length === 0) {
      this.retryLater('找不到车机：请连上车机的热点，或在下面填车机地址');
      return;
    }
    this.attempt(list, '');
  }

  private attempt(list: Endpoint[], why: string): void {
    if (!this.active) {
      return;
    }
    if (list.length === 0) {
      this.retryLater(why);
      return;
    }
    const c = new Conn(list[0]);
    const rest = list.slice(1);
    this.conn = c;
    this.ch = null;
    this.streaming = false;
    this.lastHeard = Date.now();
    this.post('status', `正在连接 ${c.ep.toString()}…`);
    c.onError = (r: string) => this.dropped(c, r, rest);
    c.onFrame = (f: Frame) => this.received(c, f);
    c.open(wifiNet()).then(() => c.send(MAGIC));
  }

  private dropped(c: Conn, why: string, rest: Endpoint[]): void {
    if (c !== this.conn) {
      return;
    }
    this.conn = null;
    const authed = this.ch !== null;
    this.ch = null;
    this.streaming = false;
    if (authed) {
      this.retryLater(why); // 认证之后断开就退避重连；之前失败换下一个地址
    } else {
      this.attempt(rest, why);
    }
  }

  private retryLater(why: string): void {
    if (!this.active) {
      return;
    }
    this.post('status', `${why}，${this.backoff} 秒后重试`);
    this.retry = setTimeout(() => this.connect(), this.backoff * 1000);
    this.backoff = Math.min(this.backoff * 2, 8);
  }

  private received(c: Conn, raw: Frame): void {
    const ch = this.ch;
    if (c !== this.conn) {
      return;
    }
    if (ch === null) {
      this.challenged(c, raw);
      return;
    }
    let f: Frame;
    try {
      f = ch.open(raw);
    } catch (e) {
      // 认证没通过时车机发的是明文 BYE。它没有认证，同一热点上谁都能冒充车机发，
      // 所以只当普通失败处理；连续多次才停止并提示重新配对
      if (raw.type === Msg.BYE && !this.streaming) {
        this.rejected++;
        if (this.rejected >= 3) {
          this.finish('车机多次拒绝了这台手机，可能在车机上清除过配对。请重新配对');
          return;
        }
      }
      c.fail(raw.type === Msg.BYE ? '车机拒绝了连接：' + text(raw.payload) : '解密失败'); // 解密失败立刻断开
      return;
    }
    this.lastHeard = Date.now();
    if (f.type === Msg.HELLO) {
      this.onHello(c, ch, f.payload);
    } else if (f.type === Msg.REQUEST_KEYFRAME) {
      this.needKey(true);
    } else if (f.type === Msg.BYE) {
      c.fail('车机断开：' + text(f.payload));
    }
    // PING 只说明车机还在；TOUCH / KEY / LAUNCH 忽略
  }

  private challenged(c: Conn, f: Frame): void {
    try {
      const a = authenticate(f, this.phoneId, (id: string) => this.ltks.get(id), random(16));
      this.ch = a.channel;
      c.max = MAX_INCOMING;
      this.lastHeard = Date.now();
      c.send(a.reply);
    } catch (e) {
      c.fail((e as Error).message); // 没配对过这台车机等：试下一个地址
    }
  }

  /** 车机的 HELLO 能解开，说明车机也持有 LTK。按 HELLO 的尺寸开始录屏；已经在录就重发 SPS/PPS 并要关键帧。 */
  private async onHello(c: Conn, ch: SecureChannel, p: Uint8Array): Promise<void> {
    let h: Hello;
    try {
      h = Hello.parse(p);
    } catch (e) {
      c.fail((e as Error).message);
      return;
    }
    await c.send(ch.seal(Msg.HELLO_ACK, helloAck())); // displayId = -1：不能反向控制
    if (c !== this.conn || !this.active) {
      return;
    }
    this.streaming = true;
    this.backoff = 1;
    this.rejected = 0;
    this.post('good', c.ep.toString(), toHex(p));
    const cur = this.hello;
    if (cur === null || cur.width !== h.width || cur.height !== h.height) {
      // ponytail: 车机换了分辨率只能重开录屏，会再弹一次确认框；同一台车机不会变
      this.startCapture(h);
    } else {
      const csd = this.config;
      if (csd !== null) {
        c.send(ch.seal(Msg.VIDEO_CONFIG, videoConfig(h.width, h.height, csd)));
      }
      this.post('status', this.started ? '正在投屏' : '请在弹出的窗口里允许录屏');
    }
    this.needKey(true);
  }

  private startCapture(h: Hello): void {
    if (this.hello !== null) {
      cast.stop();
    }
    this.hello = h;
    this.started = false;
    this.config = null;
    this.gen++;
    const gen = this.gen;
    const err: number = cast.start(h.width, h.height, h.fps, h.bitrate,
      (kind: number, value: number, data: ArrayBuffer, pts: number) => {
        if (gen === this.gen) {
          this.onNative(kind, value, data, pts);
        }
      });
    if (err !== 0) {
      this.finish(`无法开始录屏（错误 ${err}）`);
    } else {
      this.post('status', '请在弹出的窗口里允许录屏');
    }
  }

  private onNative(kind: number, value: number, data: ArrayBuffer, pts: number): void {
    const c = this.conn;
    const ch = this.ch;
    const h = this.hello;
    if (kind === CONFIG) {
      this.config = new Uint8Array(data);
      if (c !== null && ch !== null && h !== null && this.streaming) {
        c.send(ch.seal(Msg.VIDEO_CONFIG, videoConfig(h.width, h.height, this.config)));
      }
    } else if (kind === FRAME) {
      if (c === null || ch === null || !this.streaming) {
        return; // 没连上车机时编码器照常跑，录屏不停
      }
      const key = value === 1;
      // 不排队：已有 2 帧在发就丢（关键帧放宽到 4），之后一直丢到下一个关键帧（P 帧缺了参考会花屏）
      if (c.inflight >= (key ? 4 : 2) || (!key && this.waitKey)) {
        this.needKey(false);
        return;
      }
      this.waitKey = false;
      c.send(ch.seal(Msg.VIDEO_FRAME, videoFrame(pts, key, new Uint8Array(data))));
    } else if (kind === STATE) {
      this.captureState(value);
    } else if (kind === ERROR) {
      this.finish(`录屏出错（${value}）`);
    } else if (kind === GAP) {
      this.needKey(false);
    }
  }

  /** 从下一个关键帧开始发。车机最多每秒要一次；自己丢帧时也最多每秒要一次。 */
  private needKey(fromCar: boolean): void {
    this.waitKey = true;
    if (fromCar || Date.now() - this.keyAt >= 1000) {
      this.keyAt = Date.now();
      cast.requestKeyframe();
    }
  }

  private captureState(s: number): void {
    if (s === 0) {
      this.started = true;
      cast.setMaxFps(this.hello?.fps ?? 30);
      this.post('status', this.streaming ? '正在投屏' : '已允许录屏，正在连接车机…');
    } else if (s === 1) {
      this.finish('没有允许录屏，已停止投屏');
    } else if (s === 2) {
      this.finish('已在状态栏停止录屏');
    } else if (s === 3) {
      this.finish('录屏被其他应用打断');
    } else if (s === 4) {
      this.finish('来电时系统停止了录屏');
    } else if (s === 10) {
      this.finish('切换了系统用户，录屏已停止');
    } else if (s === 8) {
      this.notice('手机上正在显示隐私内容，这部分画面被遮挡');
    }
  }

  private notice(msg: string): void {
    const c = this.conn;
    const ch = this.ch;
    if (c !== null && ch !== null && this.streaming) {
      c.send(ch.seal(Msg.NOTICE, utf8(msg)));
    }
  }

  /** 结束投屏：告诉车机、停录屏、通知 UI 停后台任务。 */
  private finish(why: string): void {
    if (!this.active) {
      return;
    }
    this.active = false;
    clearInterval(this.watchdog);
    clearTimeout(this.retry);
    const c = this.conn;
    const ch = this.ch;
    if (c !== null) {
      if (ch !== null && this.streaming) {
        c.send(ch.seal(Msg.BYE, utf8('手机停止了投屏')));
      }
      c.close();
    }
    this.conn = null;
    this.ch = null;
    this.streaming = false;
    if (this.hello !== null) {
      cast.stop();
      this.hello = null;
      this.gen++;
    }
    this.config = null;
    this.post('stopped', why);
  }

  private post(type: string, t: string, hello?: string): void {
    const m: FromWorker = { type: type, text: t, hello: hello };
    port.postMessage(m);
  }
}

const caster = new Caster();
port.onmessage = (e: MessageEvents) => caster.handle(e.data as ToWorker);
