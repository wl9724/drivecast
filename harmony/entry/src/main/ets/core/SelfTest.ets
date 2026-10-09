import {
  PairingClient, SecureChannel, authTag, authenticate, commitment, longTermKey, privateKey, publicKey, random,
  sessionKeys, sharedX
} from './Crypto';
import { Frame, Msg, cat, encodeFrame, hex, same, utf8 } from './Proto';

// 协议自检：用系统 cryptoFramework 跑 docs/testvectors/ios-pairing.json 的全部向量（App 里「协议自检」和 ohosTest 共用）。

interface Round {
  i: number;
  bit: number;
  nP: string;
  nC: string;
  commitP: string;
  commitC: string;
}

interface Sealed {
  dir: string;
  type: number;
  payload: string;
  sealed: string;
}

interface Vectors {
  dP: string;
  dC: string;
  pkP: string;
  pkC: string;
  carId: string;
  phoneId: string;
  code: number;
  rounds: Round[];
  z: string;
  ltk: string;
  nonceC: string;
  nonceP: string;
  tag: string;
  kP2C: string;
  kC2P: string;
  frames: Sealed[];
}

// P-256 基点 G：ECDH(d, G) 的 x 坐标就是公钥 d·G 的 x 坐标
const G = hex('046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296' +
  '4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5');

function frameOf(b: Uint8Array): Frame {
  return new Frame(b[0], b.slice(5));
}

function rejects(f: () => void): boolean {
  try {
    f();
  } catch (e) {
    return true;
  }
  return false;
}

/** 全部通过返回每一项的说明；有一项不对就抛错（消息里写哪一项）。 */
export function runSelfTest(json: string): string[] {
  const v = JSON.parse(json) as Vectors;
  const log: string[] = [];
  const check = (name: string, ok: boolean): void => {
    if (!ok) {
      throw new Error('不通过：' + name);
    }
    log.push('通过：' + name);
  };
  const carId = hex(v.carId);
  const phoneId = hex(v.phoneId);
  const pkP = hex(v.pkP);
  const pkC = hex(v.pkC);
  const ltk = hex(v.ltk);
  const nonceC = hex(v.nonceC);
  const nonceP = hex(v.nonceP);
  const skP = privateKey(hex(v.dP));
  const skC = privateKey(hex(v.dC));

  check('私钥 dP、dC 对应向量里的公钥', same(sharedX(skP, G), pkP.subarray(1, 33)) &&
    same(sharedX(skC, G), pkC.subarray(1, 33)));
  let commits = v.rounds.length === 20;
  for (const r of v.rounds) {
    commits = commits && r.bit === ((v.code >> r.i) & 1) &&
      same(commitment('DCv1 P', pkP, pkC, hex(r.nP), r.bit), hex(r.commitP)) &&
      same(commitment('DCv1 C', pkC, pkP, hex(r.nC), r.bit), hex(r.commitC));
  }
  check('20 轮承诺 commitP / commitC', commits);
  check('ECDH 共享密钥 Z（双向）', same(sharedX(skP, pkC), hex(v.z)) && same(sharedX(skC, pkP), hex(v.z)));
  check('LTK（HKDF-SHA256）', same(longTermKey(hex(v.z), carId, phoneId, pkP, pkC), ltk));
  check('认证 tag（HMAC-SHA256）', same(authTag(ltk, carId, phoneId, nonceC, nonceP), hex(v.tag)));
  check('会话密钥 kP2C / kC2P', same(sessionKeys(ltk, carId, phoneId, nonceC, nonceP), cat([hex(v.kP2C), hex(v.kC2P)])));

  // 手机这头：认证、加密 p2c 帧（含空负载）、解密 c2p 帧
  const challenge = new Frame(Msg.AUTH_CHALLENGE, cat([carId, nonceC]));
  const a = authenticate(challenge, phoneId, (id: string) => id === v.carId ? ltk : undefined, nonceP);
  check('AUTH_RESPONSE', same(a.reply, encodeFrame(Msg.AUTH_RESPONSE, cat([phoneId, nonceP, hex(v.tag)]))));
  check('没配对过的车机', rejects(() => {
    authenticate(challenge, phoneId, (id: string) => undefined, nonceP);
  }));
  for (const f of v.frames) {
    if (f.dir === 'p2c') {
      check(`加密帧 0x${f.type.toString(16)}（负载 ${f.payload.length / 2} 字节）`,
        same(a.channel.seal(f.type, hex(f.payload)), hex(f.sealed)));
    } else {
      const p = a.channel.open(frameOf(hex(f.sealed)));
      check(`解密帧 0x${f.type.toString(16)}`, p.type === f.type && same(p.payload, hex(f.payload)));
    }
  }

  // 车机这头解手机的帧：重放、乱序、篡改都要拒绝
  const f0 = frameOf(hex(v.frames[0].sealed));
  const f1 = frameOf(hex(v.frames[1].sealed));
  const car = (): SecureChannel => new SecureChannel(hex(v.kC2P), hex(v.kP2C));
  const c = car();
  check('解密 frame0 和空负载帧', same(c.open(f0).payload, utf8('frame0')) && c.open(f1).payload.length === 0);
  const replay = car();
  replay.open(f0);
  check('拒绝重放', rejects(() => {
    replay.open(f0);
  }));
  check('拒绝乱序 / 丢帧', rejects(() => {
    car().open(f1);
  }));
  const t0 = f0.payload.slice();
  t0[t0.length - 1] ^= 1;
  check('拒绝篡改密文', rejects(() => {
    car().open(new Frame(f0.type, t0));
  }));
  check('拒绝篡改帧头（AAD）', rejects(() => {
    car().open(new Frame(Msg.VIDEO_CONFIG, f0.payload));
  }));
  const t1 = f1.payload.slice();
  t1[0] ^= 1;
  check('拒绝篡改空负载帧的 tag', rejects(() => {
    const ch = car();
    ch.open(f0);
    ch.open(new Frame(f1.type, t1));
  }));
  check('拒绝比 tag 还短的帧', rejects(() => {
    car().open(new Frame(f0.type, new Uint8Array(15)));
  }));

  // 不在曲线上的点
  const bad = pkC.slice();
  bad[64] ^= 1;
  check('拒绝不在曲线上的公钥', rejects(() => {
    publicKey(bad);
  }) && rejects(() => {
    publicKey(cat([new Uint8Array([2]), pkC.subarray(1)]));
  }));

  // 配对状态机：和向量逐帧核对
  const used = new Set<number>();
  const queue = v.rounds.map((r: Round) => hex(r.nP));
  queue.push(nonceP);
  const pc = new PairingClient(phoneId, '测试手机', skP, pkP, (n: number) => queue.shift() ?? random(n), used);
  let ok = same(pc.receive(challenge).send ?? new Uint8Array(0),
    encodeFrame(Msg.PAIR_START, cat([phoneId, pkP, utf8('测试手机')])));
  ok = ok && pc.receive(new Frame(Msg.PAIR_KEY, pkC)).needCode;
  let out = pc.submit(v.code);
  for (const r of v.rounds) {
    ok = ok && same(out, encodeFrame(Msg.PAIR_COMMIT, hex(r.commitP)));
    ok = ok && same(pc.receive(new Frame(Msg.PAIR_COMMIT, hex(r.commitC))).send ?? new Uint8Array(0),
      encodeFrame(Msg.PAIR_REVEAL, hex(r.nP)));
    out = pc.receive(new Frame(Msg.PAIR_REVEAL, hex(r.nC))).send ?? new Uint8Array(0);
  }
  ok = ok && same(out, encodeFrame(Msg.AUTH_RESPONSE, cat([phoneId, nonceP, hex(v.tag)])));
  const notice = frameOf(new SecureChannel(hex(v.kC2P), hex(v.kP2C)).seal(Msg.NOTICE, utf8('paired')));
  ok = ok && pc.receive(notice).paired && same(pc.carId, carId) && same(pc.ltk, ltk);
  check('完整配对流程（20 轮承诺 / 揭示 + 确认）', ok);

  const atCode = (): PairingClient => {
    const p = new PairingClient(phoneId, '测试手机', skP, pkP, random, used);
    p.receive(challenge);
    p.receive(new Frame(Msg.PAIR_KEY, pkC));
    return p;
  };
  check('拒绝用过的配对码', rejects(() => {
    atCode().submit(v.code);
  }) && rejects(() => {
    atCode().submit(1000000);
  }));
  const wrong = atCode();
  wrong.submit(654321);
  wrong.receive(new Frame(Msg.PAIR_COMMIT, hex(v.rounds[0].commitC)));
  check('车机揭示和承诺不符时中止', rejects(() => {
    wrong.receive(new Frame(Msg.PAIR_REVEAL, hex(v.rounds[1].nC)));
  }));
  check('配对时拒绝不在曲线上的车机公钥', rejects(() => {
    const p = new PairingClient(phoneId, '测试手机', skP, pkP, random, used);
    p.receive(challenge);
    p.receive(new Frame(Msg.PAIR_KEY, bad));
  }));
  return log;
}
