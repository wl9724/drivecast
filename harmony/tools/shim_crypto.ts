// 用 node:crypto 模拟本项目用到的那一小部分 cryptoFramework（语义按鸿蒙文档：GCM 加密输出 密文 || tag，
// 解密 tag 放参数里；不给数据的 doFinal 不算 AAD，所以这里直接报错，空负载必须走自己算的 tag）。
import * as c from 'node:crypto';

class PubKey {
  k: c.KeyObject;
  constructor(k: c.KeyObject) { this.k = k; }
  getEncoded() { return { data: new Uint8Array(this.k.export({ type: 'spki', format: 'der' })) }; }
}

class PriKey {
  k: c.KeyObject;
  constructor(k: c.KeyObject) { this.k = k; }
}

export const cryptoFramework = {
  CryptoMode: { ENCRYPT_MODE: 0, DECRYPT_MODE: 1 },
  createAsyKeyGenerator(alg: string) {
    return {
      generateKeyPairSync() {
        const kp = c.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
        return { pubKey: new PubKey(kp.publicKey), priKey: new PriKey(kp.privateKey) };
      },
      convertKeySync(pub: any, pri: any) {
        return {
          pubKey: pub ? new PubKey(c.createPublicKey({ key: Buffer.from(pub.data), format: 'der', type: 'spki' })) : null,
          priKey: pri ? new PriKey(c.createPrivateKey({ key: Buffer.from(pri.data), format: 'der', type: 'pkcs8' })) : null,
        };
      },
    };
  },
  createKeyAgreement(alg: string) {
    return {
      generateSecretSync(pri: PriKey, pub: PubKey) {
        return { data: new Uint8Array(c.diffieHellman({ privateKey: pri.k, publicKey: pub.k })) };
      },
    };
  },
  createSymKeyGenerator(alg: string) {
    return { convertKeySync(b: any) { return { raw: Buffer.from(b.data) }; } };
  },
  createMac(alg: string) {
    let h: c.Hmac;
    return {
      initSync(k: any) { h = c.createHmac('sha256', k.raw); },
      updateSync(b: any) { h.update(b.data); },
      doFinalSync() { return { data: new Uint8Array(h.digest()) }; },
    };
  },
  createKdf(alg: string) {
    return {
      generateSecretSync(s: any) {
        return { data: new Uint8Array(c.hkdfSync('sha256', s.key, s.salt, s.info, s.keySize)) };
      },
    };
  },
  createRandom() {
    return { generateRandomSync(n: number) { return { data: new Uint8Array(c.randomBytes(n)) }; } };
  },
  createCipher(t: string) {
    let mode = 0;
    let key: Buffer;
    let p: any;
    return {
      initSync(m: number, k: any, params: any) { mode = m; key = k.raw; p = params; },
      updateSync(b: any) { throw new Error('不该调 update：和 doFinal 一起用会把 AAD 算两遍'); },
      doFinalSync(b: any) {
        if (t === 'AES128|ECB|NoPadding') {
          const e = c.createCipheriv('aes-128-ecb', key, null);
          e.setAutoPadding(false);
          return { data: new Uint8Array(Buffer.concat([e.update(b.data), e.final()])) };
        }
        if (!b || b.data.length === 0) {
          throw new Error('空的 doFinal：鸿蒙 GCM 这时不算 AAD');
        }
        if (mode === 0) {
          const e = c.createCipheriv('aes-128-gcm', key, p.iv.data);
          e.setAAD(p.aad.data);
          const ct = Buffer.concat([e.update(b.data), e.final()]);
          return { data: new Uint8Array(Buffer.concat([ct, e.getAuthTag()])) };
        }
        const d = c.createDecipheriv('aes-128-gcm', key, p.iv.data);
        d.setAAD(p.aad.data);
        d.setAuthTag(p.authTag.data);
        return { data: new Uint8Array(Buffer.concat([d.update(b.data), d.final()])) };
      },
    };
  },
};
