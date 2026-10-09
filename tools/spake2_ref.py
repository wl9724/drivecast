"""adb 无线调试配对用的 SPAKE2（BoringSSL spake25519）参考模型，车机 car-android/.../adb/Spake2.kt 照它写。
Run: python3 -I tools/spake2_ref.py                 自检：打印并核对 AdbCryptoTest 里的向量
     python3 -I tools/spake2_ref.py interop ./party  和 BoringSSL 对跑（两种角色 + 错误密码）

party 是链接 BoringSSL 的小程序（cc party.c -Iinclude libcrypto.a -lpthread）：
  argv: alice|bob <密码 hex>；打印自己的消息 hex，从 stdin 读对方消息 hex，打印 64 字节密钥 hex。
  用 SPAKE2_CTX_new(role, "adb pair client"/"adb pair server"（sizeof，含 \\0）, ...)、
  SPAKE2_generate_msg、SPAKE2_process_msg。2026-10 用 BoringSSL 44979b8 核对过。"""
import hashlib, hmac, os, subprocess, sys

p = 2**255 - 19
l = 2**252 + 27742317777372353535851937790883648493
d = -121665 * pow(121666, p - 2, p) % p
SQRT_M1 = pow(2, (p - 1) // 4, p)
inv = lambda x: pow(x, p - 2, p)

def decode(b):  # RFC 8032；和 BoringSSL 一样只要求在曲线上
    y = int.from_bytes(b, "little"); sign = y >> 255; y = (y & ((1 << 255) - 1)) % p
    xx = (y * y - 1) * inv(d * y * y + 1) % p
    x = pow(xx, (p + 3) // 8, p)
    if (x * x - xx) % p: x = x * SQRT_M1 % p
    if (x * x - xx) % p: raise ValueError("not on curve")
    if (x & 1) != sign: x = (p - x) % p
    return (x, y)

def encode(P):
    x, y = P
    return (y | ((x & 1) << 255)).to_bytes(32, "little")

def add(P, Q):
    (x1, y1), (x2, y2) = P, Q
    t = d * x1 * x2 * y1 * y2 % p
    return ((x1 * y2 + x2 * y1) * inv(1 + t) % p, (y1 * y2 + x1 * x2) * inv(1 - t) % p)

def mul(k, P):
    R = (0, 1)
    while k:
        if k & 1: R = add(R, P)
        P = add(P, P); k >>= 1
    return R

B = decode(bytes.fromhex("5866666666666666666666666666666666666666666666666666666666666666"))
M = decode(bytes.fromhex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
N = decode(bytes.fromhex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))
CLIENT, SERVER = b"adb pair client\0", b"adb pair server\0"  # 各 16 字节，含 \0

def lp(b): return len(b).to_bytes(8, "little") + b

class Spake2:
    def __init__(self, alice, password, rand64):
        self.alice = alice
        self.x = (int.from_bytes(rand64, "little") % l) * 8
        self.pw_hash = hashlib.sha512(password).digest()
        w = int.from_bytes(self.pw_hash, "little") % l
        for i in range(3):  # BoringSSL 的 password scalar hack（M、N 在素数阶子群里，不影响结果）
            if (w >> i) & 1: w += l << i
        self.w = w
        self.msg = encode(add(mul(self.x, B), mul(w, M if alice else N)))

    def process(self, their_msg):
        m = mul(self.w, N if self.alice else M)
        K = encode(mul(self.x, add(decode(their_msg), ((-m[0]) % p, m[1]))))
        a, b = (self.msg, their_msg) if self.alice else (their_msg, self.msg)
        return hashlib.sha512(lp(CLIENT) + lp(SERVER) + lp(a) + lp(b) + lp(K) + lp(self.pw_hash)).digest()

def aes_key(key64):  # HKDF-SHA256，salt 为空（= 32 个 0），info 不含 \0，16 字节
    prk = hmac.new(b"\0" * 32, key64, hashlib.sha256).digest()
    return hmac.new(prk, b"adb pairing_auth aes-128-gcm key\x01", hashlib.sha256).digest()[:16]

def selftest():
    pw = b"123456" + bytes(range(64))  # 配对码 + 64 字节 TLS exporter
    A, Bo = Spake2(True, pw, b"\x11" * 64), Spake2(False, pw, b"\x22" * 64)
    k = A.process(Bo.msg)
    assert k == Bo.process(A.msg)
    assert A.msg.hex() == "e24a22e895375f3449ee318faf0a57b6cbdc7e2cac90354f2e988316099c6615"
    assert Bo.msg.hex() == "e972fae2cb51943c37f0f2f1ad744fa092ea8802051e338df70f70a91f1e967b"
    assert k.hex() == ("e64b8c8a760d86d048bcf7dfa0c8140eb81948ff49524cc8eba15950cb73ff7d"
                       "69a068d424db0bfa9487bb887759275f1e1bd496277b00fc204739eb1dedd9e0")
    assert aes_key(k).hex() == "d6b9ec542301342ce97cda5c45b30222"
    wrong = Spake2(False, b"123457" + bytes(range(64)), b"\x22" * 64)
    assert A.process(wrong.msg) != wrong.process(A.msg)
    try: decode((2).to_bytes(32, "little")); raise AssertionError("y=2 应当不在曲线上")
    except ValueError: pass
    for name, v in [("alice msg", A.msg), ("bob msg", Bo.msg), ("spake2 key", k), ("aes128 key", aes_key(k))]:
        print("%-11s %s" % (name, v.hex()))
    print("OK")

def interop(party):
    for trial in range(6):
        pw = b"%06d" % (trial * 137) + os.urandom(64)
        for py_alice in (True, False):
            me = Spake2(py_alice, pw, os.urandom(64))
            c = subprocess.Popen([party, "bob" if py_alice else "alice", pw.hex()], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
            their = bytes.fromhex(c.stdout.readline().strip())
            c.stdin.write(me.msg.hex() + "\n"); c.stdin.flush()
            assert me.process(their).hex() == c.stdout.readline().strip(), ("key mismatch", trial, py_alice)
            c.wait()
    print("OK: model == BoringSSL spake25519 (both roles, 12 runs)")

if __name__ == "__main__":
    interop(sys.argv[2]) if sys.argv[1:2] == ["interop"] else selftest()
