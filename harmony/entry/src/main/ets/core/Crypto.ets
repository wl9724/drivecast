import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { Frame, Msg, cat, encodeFrame, header, hex, same, text, toHex, utf8 } from './Proto';

// 手机 ↔ 车机的配对、认证和加密帧，规范见 docs/protocol.md「iPhone：TCP + 配对 + 加密」，
// 参考模型 tools/pairing_ref.py，测试向量 docs/testvectors/ios-pairing.json。与 ios/Core 的 Pairing.swift 一一对应。

// RFC 5480 SPKI 头（P-256 未压缩点），后面接 65 字节 04 || X || Y
const SPKI_P256 = hex('3059301306072a8648ce3d020106082a8648ce3d030107034200');
// PKCS#8 头（P-256、不带公钥），后面接 32 字节私钥。只有自检用固定私钥
const PKCS8_P256 = hex('3041020100301306072a8648ce3d020106082a8648ce3d030107042730250201010420');
const P = BigInt('0xffffffff00000001000000000000000000000000ffffffffffffffffffffffff');
const B = BigInt('0x5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b');
const R = BigInt('0xe1000000000000000000000000000000');
const ZERO = BigInt(0);
const ONE = BigInt(1);

const eccGen = cryptoFramework.createAsyKeyGenerator('ECC256');
const ecdh = cryptoFramework.createKeyAgreement('ECC256');
const hmacGen = cryptoFramework.createSymKeyGenerator('HMAC');
const aesGen = cryptoFramework.createSymKeyGenerator('AES128');
const rng = cryptoFramework.createRandom();

export function random(n: number): Uint8Array {
  return rng.generateRandomSync(n).data;
}

function big(b: Uint8Array): bigint {
  let x = ZERO;
  for (let i = 0; i < b.length; i++) {
    x = (x << BigInt(8)) | BigInt(b[i]);
  }
  return x;
}

function bytes(x: bigint, n: number): Uint8Array {
  const out = new Uint8Array(n);
  for (let i = n - 1; i >= 0; i--) {
    out[i] = Number(x & BigInt(0xff));
    x = x >> BigInt(8);
  }
  return out;
}

export function mac(key: Uint8Array, parts: Uint8Array[]): Uint8Array {
  const m = cryptoFramework.createMac('SHA256');
  m.initSync(hmacGen.convertKeySync({ data: key }));
  m.updateSync({ data: cat(parts) });
  return m.doFinalSync().data;
}

function hkdf(ikm: Uint8Array, salt: Uint8Array, info: Uint8Array): Uint8Array {
  const spec: cryptoFramework.HKDFSpec = { algName: 'HKDF', key: ikm, salt: salt, info: info, keySize: 32 };
  return cryptoFramework.createKdf('HKDF|SHA256').generateSecretSync(spec).data;
}

export function newKey(): cryptoFramework.KeyPair {
  return eccGen.generateKeyPairSync();
}

/** 自己的公钥 → 65 字节 04 || X || Y（SPKI DER 去掉固定的 26 字节头）。 */
export function rawPublic(pk: cryptoFramework.PubKey): Uint8Array {
  const der = pk.getEncoded().data;
  if (der.length !== 91 || !same(der.subarray(0, 26), SPKI_P256)) {
    throw new Error('公钥格式不对');
  }
  return der.slice(26);
}

/** 32 字节私钥标量 → PriKey。 */
export function privateKey(d: Uint8Array): cryptoFramework.PriKey {
  return eccGen.convertKeySync(null, { data: cat([PKCS8_P256, d]) }).priKey;
}

/** y² = x³ - 3x + b (mod p)。不只依赖系统库：收到的点必须在曲线上，否则可能泄露私钥。 */
function onCurve(raw: Uint8Array): boolean {
  const x = big(raw.subarray(1, 33));
  const y = big(raw.subarray(33, 65));
  return x < P && y < P && (y * y - (x * x * x - BigInt(3) * x + B)) % P === ZERO;
}

/** 解析对方的 65 字节公钥（04 || X || Y），不在曲线上就拒绝。 */
export function publicKey(raw: Uint8Array): cryptoFramework.PubKey {
  if (raw.length !== 65 || raw[0] !== 4 || !onCurve(raw)) {
    throw new Error('车机的公钥无效');
  }
  try {
    return eccGen.convertKeySync({ data: cat([SPKI_P256, raw]) }, null).pubKey;
  } catch (e) {
    throw new Error('车机的公钥无效');
  }
}

/** ECDH 共享密钥 Z：对方公钥点乘后的 x 坐标，32 字节（RFC 5903）。 */
export function sharedX(sk: cryptoFramework.PriKey, peer: Uint8Array): Uint8Array {
  const z = ecdh.generateSecretSync(sk, publicKey(peer)).data;
  if (z.length !== 32) {
    throw new Error('ECDH 失败');
  }
  return z;
}

/** 第 i 轮的逐位承诺。label 是 "DCv1 P"（手机）或 "DCv1 C"（车机），own 是自己的公钥。 */
export function commitment(label: string, own: Uint8Array, peer: Uint8Array, nonce: Uint8Array, bit: number): Uint8Array {
  return mac(nonce, [utf8(label), own, peer, new Uint8Array([0x80 | bit])]);
}

export function longTermKey(z: Uint8Array, carId: Uint8Array, phoneId: Uint8Array, pkP: Uint8Array,
  pkC: Uint8Array): Uint8Array {
  return hkdf(z, new Uint8Array(32), cat([utf8('DCv1 LTK'), carId, phoneId, pkP, pkC]));
}

export function authTag(ltk: Uint8Array, carId: Uint8Array, phoneId: Uint8Array, nonceC: Uint8Array,
  nonceP: Uint8Array): Uint8Array {
  return mac(ltk, [utf8('DCv1 AUTH'), carId, phoneId, nonceC, nonceP]);
}

/** 每个连接的会话密钥：前 16 字节手 → 车，后 16 字节车 → 手。 */
export function sessionKeys(ltk: Uint8Array, carId: Uint8Array, phoneId: Uint8Array, nonceC: Uint8Array,
  nonceP: Uint8Array): Uint8Array {
  return hkdf(ltk, cat([nonceC, nonceP]), cat([utf8('DCv1 SESS'), carId, phoneId]));
}

/** GF(2^128) 乘法（NIST SP 800-38D 算法 1），块按大端整数存。 */
function gmul(x: bigint, y: bigint): bigint {
  let z = ZERO;
  let v = y;
  for (let i = 127; i >= 0; i--) {
    if (((x >> BigInt(i)) & ONE) === ONE) {
      z = z ^ v;
    }
    v = (v & ONE) === ONE ? (v >> ONE) ^ R : v >> ONE;
  }
  return z;
}

/** 认证之后一个方向的加密：nonce = 4 个 0 字节 || 计数器 u64 BE，AAD = 这一帧的 5 字节头。 */
class Gcm {
  private key: cryptoFramework.SymKey;
  private cipher: cryptoFramework.Cipher = cryptoFramework.createCipher('AES128|GCM|NoPadding');
  private ctr: number = 0;

  constructor(k: Uint8Array) {
    this.key = aesGen.convertKeySync({ data: k });
  }

  seal(type: number, payload: Uint8Array): Uint8Array {
    const hdr = header(type, payload.length + 16);
    const iv = this.nonce();
    if (payload.length === 0) {
      return cat([hdr, this.emptyTag(iv, hdr)]);
    }
    this.init(cryptoFramework.CryptoMode.ENCRYPT_MODE, iv, hdr, new Uint8Array(16)); // 占位的长度就是输出 tag 的长度
    return cat([hdr, this.cipher.doFinalSync({ data: payload }).data]); // 只调一次：update + doFinal 会把 AAD 算两遍
  }

  /** 解不开就抛错，调用方要立刻断开。 */
  open(f: Frame): Frame {
    const n = f.payload.length;
    const iv = this.nonce();
    if (n < 16) {
      throw new Error('解密失败');
    }
    const hdr = header(f.type, n);
    const tag = f.payload.slice(n - 16);
    if (n === 16) {
      if (!same(tag, this.emptyTag(iv, hdr))) {
        throw new Error('解密失败');
      }
      return new Frame(f.type, new Uint8Array(0));
    }
    this.init(cryptoFramework.CryptoMode.DECRYPT_MODE, iv, hdr, tag);
    try {
      return new Frame(f.type, this.cipher.doFinalSync({ data: f.payload.slice(0, n - 16) }).data);
    } catch (e) {
      throw new Error('解密失败');
    }
  }

  private nonce(): Uint8Array {
    const iv = new Uint8Array(12);
    const dv = new DataView(iv.buffer);
    dv.setUint32(4, Math.floor(this.ctr / 4294967296));
    dv.setUint32(8, this.ctr >>> 0);
    this.ctr++;
    return iv;
  }

  private init(mode: cryptoFramework.CryptoMode, iv: Uint8Array, aad: Uint8Array, tag: Uint8Array): void {
    const p: cryptoFramework.GcmParamsSpec = {
      algName: 'GcmParamsSpec', iv: { data: iv }, aad: { data: aad }, authTag: { data: tag }
    };
    this.cipher.initSync(mode, this.key, p);
  }

  /**
   * 空负载（PING、REQUEST_KEYFRAME，车机每秒都发）的 tag 自己算：系统 GCM 没有输入数据时不把 AAD 算进去，
   * 和车机、iPhone 对不上。tag = E_K(J0) ⊕ GHASH_H(AAD 补零到 16 字节 || len(AAD)·8 || 0)，H = E_K(0)。
   */
  private emptyTag(iv: Uint8Array, hdr: Uint8Array): Uint8Array {
    const ecb = cryptoFramework.createCipher('AES128|ECB|NoPadding');
    ecb.initSync(cryptoFramework.CryptoMode.ENCRYPT_MODE, this.key, null);
    const j0 = new Uint8Array(16);
    j0.set(iv);
    j0[15] = 1;
    const e = ecb.doFinalSync({ data: cat([new Uint8Array(16), j0]) }).data; // H || E_K(J0)
    const h = big(e.subarray(0, 16));
    const a = new Uint8Array(16);
    a.set(hdr);
    const s = gmul(gmul(big(a), h) ^ (BigInt(hdr.length * 8) << BigInt(64)), h);
    return bytes(big(e.subarray(16, 32)) ^ s, 16);
  }
}

/** 认证之后双向的加密帧：type u8 · len u32 BE（= 明文长度 + 16）· AES-128-GCM 密文 || tag。只在一个线程上用。 */
export class SecureChannel {
  private tx: Gcm;
  private rx: Gcm;

  constructor(tx: Uint8Array, rx: Uint8Array) {
    this.tx = new Gcm(tx);
    this.rx = new Gcm(rx);
  }

  seal(type: number, payload: Uint8Array): Uint8Array {
    return this.tx.seal(type, payload);
  }

  open(f: Frame): Frame {
    return this.rx.open(f);
  }
}

function challenge(f: Frame): Uint8Array[] {
  if (f.type === Msg.BYE) {
    throw new Error('车机断开：' + text(f.payload));
  }
  if (f.type !== Msg.AUTH_CHALLENGE || f.payload.length !== 32) {
    throw new Error('车机的握手消息不对');
  }
  return [f.payload.slice(0, 16), f.payload.slice(16)];
}

export class Auth {
  carId: Uint8Array;
  reply: Uint8Array;
  channel: SecureChannel;

  constructor(carId: Uint8Array, reply: Uint8Array, channel: SecureChannel) {
    this.carId = carId;
    this.reply = reply;
    this.channel = channel;
  }
}

export const NOT_PAIRED = '没有和这台车机配对过';

/**
 * 已配对时（每次投屏）回应车机的 AUTH_CHALLENGE。ltk 按 carId（十六进制）查，查不到就是没和这台车机配对过。
 * 之后双方都用返回的加密通道；手机能解开车机的 HELLO 才说明车机也持有 LTK。
 */
export function authenticate(f: Frame, phoneId: Uint8Array, lookup: (carIdHex: string) => Uint8Array | undefined,
  nonceP: Uint8Array): Auth {
  const c = challenge(f);
  const carId = c[0];
  const nonceC = c[1];
  const ltk = lookup(toHex(carId));
  if (ltk === undefined) {
    throw new Error(NOT_PAIRED);
  }
  const tag = authTag(ltk, carId, phoneId, nonceC, nonceP);
  const k = sessionKeys(ltk, carId, phoneId, nonceC, nonceP);
  return new Auth(carId, encodeFrame(Msg.AUTH_RESPONSE, cat([phoneId, nonceP, tag])),
    new SecureChannel(k.slice(0, 16), k.slice(16)));
}

/** 本次运行里用过的配对码。同一个码再用一次，旁听者或中间人就能多拿到几位，规范要求作废。 */
export const usedCodes = new Set<number>();

export class PairOut {
  send: Uint8Array | null = null;
  /** 车机已显示配对码，等用户输入后调 submit */
  needCode: boolean = false;
  /** 配对成功，carId 和 ltk 可以保存了 */
  paired: boolean = false;
}

const S_CHALLENGE = 0;
const S_KEY = 1;
const S_CODE = 2;
const S_COMMIT = 3;
const S_REVEAL = 4;
const S_CONFIRM = 5;
const S_DONE = 6;

/**
 * 配对（只在 App 前台做）。蓝牙 LE Secure Connections 的 Passkey Entry：ECDH 之后对配对码的 20 位逐位承诺、揭示，
 * 旁听者拿不到 LTK，中间人骗过车机的概率约 2×10⁻⁵。和传输无关：调用方先发 MAGIC，把收到的每一帧交给 receive。
 */
export class PairingClient {
  carId: Uint8Array = new Uint8Array(0);
  ltk: Uint8Array = new Uint8Array(0);
  private state: number = S_CHALLENGE;
  private round: number = 0;
  private channel: SecureChannel | null = null;
  private nonceC: Uint8Array = new Uint8Array(0);
  private pkC: Uint8Array = new Uint8Array(0);
  private code: number = 0;
  private nP: Uint8Array = new Uint8Array(0);
  private commitC: Uint8Array = new Uint8Array(0);
  private phoneId: Uint8Array;
  private name: string;
  private sk: cryptoFramework.PriKey;
  private pkP: Uint8Array;
  private random: (n: number) => Uint8Array;
  private used: Set<number>;

  /** sk / pkP / random / used 只在自检里换成固定值。 */
  constructor(phoneId: Uint8Array, name: string, sk: cryptoFramework.PriKey, pkP: Uint8Array,
    rand: (n: number) => Uint8Array, used: Set<number>) {
    this.phoneId = phoneId;
    this.name = name;
    this.sk = sk;
    this.pkP = pkP;
    this.random = rand;
    this.used = used;
  }

  /** 处理车机发来的一帧。抛错时调用方断开连接。 */
  receive(f: Frame): PairOut {
    const out = new PairOut();
    if (f.type === Msg.BYE) {
      this.state = S_DONE;
      throw new Error('车机断开：' + text(f.payload));
    }
    if (this.state === S_CHALLENGE) {
      const c = challenge(f);
      this.carId = c[0];
      this.nonceC = c[1];
      this.state = S_KEY;
      out.send = encodeFrame(Msg.PAIR_START, cat([this.phoneId, this.pkP, truncated(this.name)]));
    } else if (this.state === S_KEY) {
      this.expect(f, Msg.PAIR_KEY, 65);
      publicKey(f.payload); // 校验在曲线上
      this.pkC = f.payload;
      this.state = S_CODE;
      out.needCode = true;
    } else if (this.state === S_COMMIT) {
      this.expect(f, Msg.PAIR_COMMIT, 32);
      this.commitC = f.payload;
      this.state = S_REVEAL;
      out.send = encodeFrame(Msg.PAIR_REVEAL, this.nP);
    } else if (this.state === S_REVEAL) {
      this.expect(f, Msg.PAIR_REVEAL, 16);
      if (!same(this.commitC, commitment('DCv1 C', this.pkC, this.pkP, f.payload, this.bit(this.round)))) {
        this.state = S_DONE;
        throw new Error('车机的配对码和输入的不一致，请在车机上重新开始配对');
      }
      if (this.round < 19) {
        out.send = this.nextCommit(this.round + 1);
        return out;
      }
      const z = sharedX(this.sk, this.pkC);
      this.ltk = longTermKey(z, this.carId, this.phoneId, this.pkP, this.pkC);
      const nonceP = this.random(16);
      const k = sessionKeys(this.ltk, this.carId, this.phoneId, this.nonceC, nonceP);
      this.channel = new SecureChannel(k.slice(0, 16), k.slice(16));
      this.state = S_CONFIRM;
      const tag = authTag(this.ltk, this.carId, this.phoneId, this.nonceC, nonceP);
      out.send = encodeFrame(Msg.AUTH_RESPONSE, cat([this.phoneId, nonceP, tag]));
    } else if (this.state === S_CONFIRM && this.channel !== null) {
      // 车机校验 AUTH_RESPONSE 后发加密的 NOTICE("paired")：能解开才说明车机算出了同一个 LTK
      if (this.channel.open(f).type !== Msg.NOTICE) {
        throw new Error('车机没有确认配对');
      }
      this.state = S_DONE;
      out.paired = true;
    } else {
      throw new Error('车机发来了意外的消息');
    }
    return out;
  }

  /** 用户输入车机上显示的 6 位配对码，返回第一轮承诺。 */
  submit(c: number): Uint8Array {
    if (this.state !== S_CODE) {
      throw new Error('现在不能输入配对码');
    }
    if (!Number.isInteger(c) || c < 0 || c >= 1000000) {
      throw new Error('配对码是 6 位数字');
    }
    if (this.used.has(c)) {
      throw new Error('这个配对码已经用过，请在车机上重新开始配对');
    }
    this.used.add(c);
    this.code = c;
    return this.nextCommit(0);
  }

  private nextCommit(i: number): Uint8Array {
    this.nP = this.random(16);
    this.round = i;
    this.state = S_COMMIT;
    return encodeFrame(Msg.PAIR_COMMIT, commitment('DCv1 P', this.pkP, this.pkC, this.nP, this.bit(i)));
  }

  private bit(i: number): number {
    return (this.code >> i) & 1;
  }

  private expect(f: Frame, type: number, len: number): void {
    if (f.type !== type || f.payload.length !== len) {
      throw new Error('车机发来了意外的消息');
    }
  }
}

/** 名称最多 64 字节 UTF-8，按字符截断。 */
function truncated(s: string): Uint8Array {
  let out = new Uint8Array(0);
  for (const ch of s) {
    const b = utf8(ch);
    if (out.length + b.length > 64) {
      break;
    }
    out = cat([out, b]);
  }
  return out;
}
