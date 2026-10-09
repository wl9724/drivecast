import { util } from '@kit.ArkTS';

// DriveCast 协议 v1，规范见 docs/protocol.md。多字节整数一律大端序。
// 消息类型与 protocol/src/main/kotlin/org/drivecast/protocol/Protocol.kt 一致。
export class Msg {
  static readonly HELLO: number = 0x01;
  static readonly HELLO_ACK: number = 0x02;
  static readonly VIDEO_CONFIG: number = 0x10;
  static readonly VIDEO_FRAME: number = 0x11;
  static readonly REQUEST_KEYFRAME: number = 0x12;
  static readonly TOUCH: number = 0x20;
  static readonly KEY: number = 0x21;
  static readonly LAUNCH: number = 0x31;
  static readonly PING: number = 0x40;
  static readonly AUTH_CHALLENGE: number = 0x50;
  static readonly AUTH_RESPONSE: number = 0x51;
  static readonly PAIR_START: number = 0x52;
  static readonly PAIR_KEY: number = 0x53;
  static readonly PAIR_COMMIT: number = 0x54;
  static readonly PAIR_REVEAL: number = 0x55;
  static readonly NOTICE: number = 0x7E;
  static readonly BYE: number = 0x7F;
}

export const VERSION = 1;
/** 车机 TCP 端口（被占用时车机改用随机端口，mDNS 里能查到）。 */
export const CAR_PORT = 27420;
export const SERVICE_TYPE = '_drivecast._tcp';
/** 认证之前每帧负载不超过 256 字节，超过就断开。 */
export const MAX_HANDSHAKE = 256;
/** 手机只收车机的控制消息，都很小。 */
export const MAX_INCOMING = 1 << 16;
/** 车机每秒发 PING；手机这么久收不到任何消息就当连接断了。 */
export const PHONE_WATCHDOG_MS = 5000;

const encoder = new util.TextEncoder();
const decoder = util.TextDecoder.create('utf-8');

export function utf8(s: string): Uint8Array {
  return encoder.encodeInto(s);
}

export function text(b: Uint8Array): string {
  return decoder.decodeToString(b);
}

/** iPhone / 鸿蒙连上车机后先发这 4 字节（明文）。 */
export const MAGIC = utf8('DCv1');

/** 拼成一个新的、长度正好的数组（socket.send 发的是整个底层 ArrayBuffer）。 */
export function cat(parts: Uint8Array[]): Uint8Array {
  let n = 0;
  for (const p of parts) {
    n += p.length;
  }
  const out = new Uint8Array(n);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

export function hex(s: string): Uint8Array {
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(s.substring(i * 2, i * 2 + 2), 16);
  }
  return out;
}

export function toHex(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i++) {
    s += (b[i] < 16 ? '0' : '') + b[i].toString(16);
  }
  return s;
}

/** 常数时间比较，不因第一个不同字节的位置泄露时间。 */
export function same(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let d = 0;
  for (let i = 0; i < a.length; i++) {
    d |= a[i] ^ b[i];
  }
  return d === 0;
}

function be(v: number, n: number): Uint8Array {
  const out = new Uint8Array(n);
  for (let i = n - 1; i >= 0; i--) {
    out[i] = v % 256;
    v = Math.floor(v / 256);
  }
  return out;
}

export class Frame {
  type: number;
  payload: Uint8Array;

  constructor(type: number, payload: Uint8Array) {
    this.type = type;
    this.payload = payload;
  }
}

/** type u8 · len u32 BE */
export function header(type: number, len: number): Uint8Array {
  return cat([new Uint8Array([type]), be(len, 4)]);
}

/** 整帧编成一块：一次 send 一帧，帧之间不会交错。 */
export function encodeFrame(type: number, payload: Uint8Array): Uint8Array {
  return cat([header(type, payload.length), payload]);
}

/** 车 → 手：车机画面区域和期望的编码参数。 */
export class Hello {
  width: number = 0;
  height: number = 0;
  dpi: number = 0;
  fps: number = 0;
  bitrate: number = 0;

  static parse(p: Uint8Array): Hello {
    if (p.length < 12) {
      throw new Error('HELLO 太短');
    }
    if (p[0] !== VERSION) {
      throw new Error(`协议版本不一致：车机 ${p[0]}，手机 ${VERSION}`);
    }
    const h = new Hello();
    h.width = p[1] << 8 | p[2];
    h.height = p[3] << 8 | p[4];
    h.dpi = p[5] << 8 | p[6];
    h.fps = p[7];
    h.bitrate = ((p[8] << 24) | (p[9] << 16) | (p[10] << 8) | p[11]) >>> 0;
    return h;
  }
}

/** 手 → 车：displayId = -1 表示不能反向控制。 */
export function helloAck(): Uint8Array {
  return new Uint8Array([VERSION, 0xff, 0xff, 0xff, 0xff]);
}

/** 手 → 车：H.264 SPS/PPS（Annex B），车机据此（重新）创建解码器。 */
export function videoConfig(width: number, height: number, csd: Uint8Array): Uint8Array {
  return cat([be(width, 2), be(height, 2), csd]);
}

/** 手 → 车：一帧 H.264（Annex B）。 */
export function videoFrame(ptsUs: number, key: boolean, data: Uint8Array): Uint8Array {
  return cat([be(Math.max(0, Math.floor(ptsUs)), 8), new Uint8Array([key ? 1 : 0]), data]);
}

export class Endpoint {
  host: string;
  port: number;

  constructor(host: string, port: number) {
    this.host = host;
    this.port = port;
  }

  toString(): string {
    return this.host.includes(':') ? `[${this.host}]:${this.port}` : `${this.host}:${this.port}`;
  }

  /** "192.168.43.1"、"192.168.43.1:27420"、"[fe80::1]:27420"、"fe80::1"，不写端口就是 27420。 */
  static parse(s: string): Endpoint | null {
    let host = s.trim();
    let port = CAR_PORT;
    if (host.startsWith('[')) {
      const end = host.indexOf(']');
      if (end < 0) {
        return null;
      }
      const rest = host.substring(end + 1);
      if (rest.startsWith(':')) {
        port = Number(rest.substring(1));
      } else if (rest.length > 0) {
        return null;
      }
      host = host.substring(1, end);
    } else if (host.split(':').length === 2) {
      const i = host.indexOf(':');
      port = Number(host.substring(i + 1));
      host = host.substring(0, i);
    }
    if (host.length === 0 || !Number.isInteger(port) || port <= 0 || port > 65535) {
      return null;
    }
    return new Endpoint(host, port);
  }
}

// UI ↔ Worker（workers/Caster.ets）的消息

/** 已配对的车机：carId（十六进制）和 LTK（十六进制）。 */
export interface CarKey {
  id: string;
  ltk: string;
}

/** mDNS 找到的车机。 */
export interface Found {
  carId: string;
  host: string;
  port: number;
}

/** cmd：start / found / stop */
export interface ToWorker {
  cmd: string;
  phoneId?: string;
  cars?: CarKey[];
  last?: string[];
  manual?: string;
  found?: Found[];
  hello?: string; // 上次车机 HELLO 的负载（十六进制）：有就点按钮时立刻开始录屏，不等连上车机
}

/** type：status（状态文字）/ good（连上的地址，hello 是这次 HELLO 的负载）/ stopped（投屏结束，text 是原因） */
export interface FromWorker {
  type: string;
  text: string;
  hello?: string;
}
