"""DriveCast iPhone<->车机 配对/认证参考模型 + 自检 + 测试向量生成。
Run: python3 -I tools/pairing_ref.py            自检（needs `cryptography`）
     python3 -I tools/pairing_ref.py vectors    > docs/testvectors/ios-pairing.json
Asserts: RFC 5903 ECDH vector, RFC 5869 HKDF vector, honest pairing, AEAD framing,
replay/tamper rejection, and the passkey-entry MITM bound (n+1)/2^n by simulation."""
import hashlib, hmac, os, random, struct
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

def mac(k, *p): return hmac.new(k, b"".join(p), hashlib.sha256).digest()

def hkdf(ikm, salt, info, n=32):  # RFC 5869, SHA-256
    prk, t, out, i = mac(salt, ikm), b"", b"", 1
    while len(out) < n:
        t = mac(prk, t, info, bytes([i])); out += t; i += 1
    return out[:n]

def keypair(d=None):
    sk = ec.derive_private_key(d, ec.SECP256R1()) if d else ec.generate_private_key(ec.SECP256R1())
    return sk, sk.public_key().public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)  # 65 B

def ecdh(sk, pk65):  # 32-byte x-coordinate
    return sk.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), pk65))

ZERO32 = bytes(32)
def commit(label, pk_self, pk_peer, nonce, bit):
    return mac(nonce, label, pk_self, pk_peer, bytes([0x80 | bit]))
def ltk_of(z, car_id, phone_id, pk_p, pk_c):
    return hkdf(z, ZERO32, b"DCv1 LTK" + car_id + phone_id + pk_p + pk_c)
def auth_tag(ltk, car_id, phone_id, nc, np_):
    return mac(ltk, b"DCv1 AUTH", car_id, phone_id, nc, np_)
def session_keys(ltk, car_id, phone_id, nc, np_):
    k = hkdf(ltk, nc + np_, b"DCv1 SESS" + car_id + phone_id)
    return k[:16], k[16:]  # (phone->car, car->phone)

class Channel:  # one direction of the AEAD framing
    def __init__(self, key): self.g, self.ctr = AESGCM(key), 0
    def _nonce(self):
        n = bytes(4) + struct.pack(">Q", self.ctr); self.ctr += 1; return n
    def seal(self, typ, payload):
        hdr = struct.pack(">BI", typ, len(payload) + 16)
        return hdr + self.g.encrypt(self._nonce(), payload, hdr)
    def open(self, frame):
        hdr, body = frame[:5], frame[5:]
        assert struct.unpack(">BI", hdr)[1] == len(body)
        return hdr[0], self.g.decrypt(self._nonce(), body, hdr)  # raises InvalidTag

class Fail(Exception): pass

class Car:
    def __init__(self, code, nbits=20):
        self.code, self.nbits = code, nbits
        self.sk, self.pk = keypair()
    def key(self, pk_p): self.pk_p = pk_p; return self.pk
    def commit(self, i, cp):
        self.cp, self.n = cp, os.urandom(16)
        return commit(b"DCv1 C", self.pk, self.pk_p, self.n, self.code >> i & 1)
    def reveal(self, i, np_):
        if not hmac.compare_digest(self.cp, commit(b"DCv1 P", self.pk_p, self.pk, np_, self.code >> i & 1)):
            raise Fail("car: phone commitment mismatch")
        return self.n

class Phone:
    def __init__(self, typed):
        self.code = typed
        self.sk, self.pk = keypair()
    def set_peer(self, pk_c): self.pk_c = pk_c
    def commit(self, i):
        self.n = os.urandom(16)
        return commit(b"DCv1 P", self.pk, self.pk_c, self.n, self.code >> i & 1)
    def reveal(self, i, cc): self.cc = cc; return self.n
    def check(self, i, nc):
        if not hmac.compare_digest(self.cc, commit(b"DCv1 C", self.pk_c, self.pk, nc, self.code >> i & 1)):
            raise Fail("phone: car commitment mismatch")

def pair(car, phone, nbits=20):
    phone.set_peer(car.key(phone.pk))
    for i in range(nbits):
        cp = phone.commit(i); cc = car.commit(i, cp)
        np_ = phone.reveal(i, cc); nc = car.reveal(i, np_); phone.check(i, nc)

def mitm_fools_car(nbits):
    """Best one-sided strategy: learn each bit from the phone (gambling the phone session),
    then guess remaining bits against the car. Returns True if the car accepts the MITM."""
    code = random.randrange(1 << nbits)
    car, phone = Car(code, nbits), Phone(code)  # user typed the right code into the real phone
    m1sk, m1pk = keypair(); m2sk, m2pk = keypair()
    phone.set_peer(m1pk); car.key(m2pk)
    phone_alive = True
    for i in range(nbits):
        if phone_alive:
            cp = phone.commit(i); g = random.getrandbits(1); n1 = os.urandom(16)
            np_ = phone.reveal(i, commit(b"DCv1 C", m1pk, phone.pk, n1, g))
            b = 0 if cp == commit(b"DCv1 P", phone.pk, m1pk, np_, 0) else 1
            try: phone.check(i, n1)
            except Fail: phone_alive = False
        else:
            b = random.getrandbits(1)
        n2 = os.urandom(16)
        car.commit(i, commit(b"DCv1 P", m2pk, car.pk, n2, b))
        try: car.reveal(i, n2)
        except Fail: return False
    return True

def demo():
    # RFC 5903 sec 8.1 P-256 ECDH vector: shared secret is girx (x-coordinate)
    i = int("C88F01F510D9AC3F70A292DAA2316DE544E9AAB8AFE84049C62A9C57862D1433", 16)
    r = int("C6EF9C5D78AE012A011164ACB397CE2088685D8F06BF9BE0B283AB46476BEE53", 16)
    ski, pki = keypair(i); skr, pkr = keypair(r)
    girx = bytes.fromhex("D6840F6B42F6EDAFD13116E0E12565202FEF8E9ECE7DCE03812464D04B9442DE")
    assert ecdh(ski, pkr) == ecdh(skr, pki) == girx
    # RFC 5869 test case 1
    assert hkdf(bytes([0x0b] * 22), bytes(range(13)), bytes(range(0xf0, 0xfa)), 42).hex() == (
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")

    # honest pairing, then auth + encrypted session
    code = random.randrange(1_000_000)
    car, phone = Car(code), Phone(code); pair(car, phone)
    car_id, phone_id = os.urandom(16), os.urandom(16)
    lc = ltk_of(ecdh(car.sk, phone.pk), car_id, phone_id, phone.pk, car.pk)
    lp = ltk_of(ecdh(phone.sk, car.pk), car_id, phone_id, phone.pk, car.pk)
    assert lc == lp
    nc, np_ = os.urandom(16), os.urandom(16)
    assert hmac.compare_digest(auth_tag(lp, car_id, phone_id, nc, np_), auth_tag(lc, car_id, phone_id, nc, np_))
    assert auth_tag(lp, car_id, phone_id, os.urandom(16), np_) != auth_tag(lc, car_id, phone_id, nc, np_)  # replay w/ new challenge fails
    p2c, _ = session_keys(lp, car_id, phone_id, nc, np_)
    k_rx = session_keys(lc, car_id, phone_id, nc, np_)[0]
    tx = Channel(p2c)
    f1, f2 = tx.seal(0x11, b"frame1"), tx.seal(0x11, b"frame2")
    def rejects(ch, fr):
        try: ch.open(fr)
        except Exception: return True
        return False
    def after_f1():
        ch = Channel(k_rx); assert ch.open(f1) == (0x11, b"frame1"); return ch
    assert rejects(after_f1(), f1)                                  # replay
    assert rejects(after_f1(), f2[:-1] + bytes([f2[-1] ^ 1]))      # tamper
    assert rejects(after_f1(), bytes([0x12]) + f2[1:])             # header (AAD) tamper
    assert rejects(Channel(k_rx), f2)                               # reorder/drop
    assert after_f1().open(f2) == (0x11, b"frame2")

    # wrong code: someone aborts
    try: pair(Car(code), Phone((code + 1) % 1_000_000)); raise AssertionError
    except Fail: pass

    # MITM bound for one-sided (car) success with n-bit code ~ (n+1)/2^n
    n, trials = 6, 3000
    rate = sum(mitm_fools_car(n) for _ in range(trials)) / trials
    print(f"MITM fools car, n={n}: {rate:.4f} (theory {(n + 1) / 2 ** n:.4f}); n=20 -> {21 / 2 ** 20:.1e}")
    assert abs(rate - (n + 1) / 2 ** n) < 0.03
    print("ok")

if __name__ == "__main__" and len(__import__("sys").argv) == 1:
    demo()


def vectors():
    """确定性测试向量：车机端（Kotlin）和 iPhone 端（Swift）的单元测试都对照它。"""
    import json
    sha = lambda b: hashlib.sha256(b).digest()
    dP = int.from_bytes(sha(b"DriveCast test phone key"), "big") % (2**255)
    dC = int.from_bytes(sha(b"DriveCast test car key"), "big") % (2**255)
    skP, pkP = keypair(dP)
    skC, pkC = keypair(dC)
    car_id, phone_id = bytes(range(16)), bytes(range(16, 32))
    code = 123456
    rounds = []
    for i in range(20):
        bit = code >> i & 1
        nP, nC = sha(b"nP" + bytes([i]))[:16], sha(b"nC" + bytes([i]))[:16]
        rounds.append(dict(i=i, bit=bit, nP=nP.hex(), nC=nC.hex(),
                           commitP=commit(b"DCv1 P", pkP, pkC, nP, bit).hex(),
                           commitC=commit(b"DCv1 C", pkC, pkP, nC, bit).hex()))
    z = ecdh(skP, pkC)
    assert z == ecdh(skC, pkP)
    ltk = ltk_of(z, car_id, phone_id, pkP, pkC)
    nonce_c, nonce_p = bytes([0xC0] * 16), bytes([0x9F] * 16)
    p2c, c2p = session_keys(ltk, car_id, phone_id, nonce_c, nonce_p)
    tx_p, tx_c = Channel(p2c), Channel(c2p)
    frames = [dict(dir="p2c", type=0x11, payload=b"frame0".hex(), sealed=tx_p.seal(0x11, b"frame0").hex()),
              dict(dir="p2c", type=0x10, payload=b"".hex(), sealed=tx_p.seal(0x10, b"").hex()),
              dict(dir="c2p", type=0x01, payload=b"hello".hex(), sealed=tx_c.seal(0x01, b"hello").hex())]
    return json.dumps(dict(
        dP="%064x" % dP, dC="%064x" % dC, pkP=pkP.hex(), pkC=pkC.hex(),
        carId=car_id.hex(), phoneId=phone_id.hex(), code=code, rounds=rounds,
        z=z.hex(), ltk=ltk.hex(), nonceC=nonce_c.hex(), nonceP=nonce_p.hex(),
        tag=auth_tag(ltk, car_id, phone_id, nonce_c, nonce_p).hex(),
        kP2C=p2c.hex(), kC2P=c2p.hex(), frames=frames), indent=1)


if __name__ == "__main__" and len(__import__("sys").argv) > 1 and __import__("sys").argv[1] == "vectors":
    print(vectors())
