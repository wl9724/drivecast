import { connection, socket } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Endpoint, Frame, MAX_HANDSHAKE, cat } from './Proto';

/** 连着 Wi-Fi 时的 Wi-Fi 网络。车机热点不能上网时可能不是默认网络，socket 要绑到它上面。 */
export function wifiNet(): connection.NetHandle | undefined {
  try {
    for (const h of connection.getAllNetsSync()) {
      if (connection.getNetCapabilitiesSync(h).bearerTypes.includes(connection.NetBearType.BEARER_WIFI)) {
        return h;
      }
    }
  } catch (e) {
    // 没有网络或取不到：不绑定
  }
  return undefined;
}

/** Wi-Fi 的 IPv4 网关：手机连车机热点时就是车机。 */
export function gateway(net: connection.NetHandle | undefined): string {
  if (net === undefined) {
    return '';
  }
  try {
    for (const r of connection.getConnectionPropertiesSync(net).routes) {
      const a = r.gateway.address;
      if (r.hasGateway && a.length > 0 && !a.includes(':') && a !== '0.0.0.0') {
        return a;
      }
    }
  } catch (e) {
    // 取不到路由就跳过这一步
  }
  return '';
}

function reason(e: Object): string {
  const err = e as BusinessError;
  return err.message ? err.message : `错误 ${err.code}`;
}

/** 到车机的一条 TCP 连接，按帧收发。出错只回调一次 onError，回调前连接已关闭。 */
export class Conn {
  ep: Endpoint;
  /** 收到的帧负载上限：认证之前 256 字节，之后调大 */
  max: number = MAX_HANDSHAKE;
  /** 已交给 send、还没发完的帧数 */
  inflight: number = 0;
  onFrame: (f: Frame) => void = (f: Frame) => {
  };
  onError: (reason: string) => void = (r: string) => {
  };
  private tcp: socket.TCPSocket = socket.constructTCPSocketInstance();
  private buf: Uint8Array = new Uint8Array(0);
  private chain: Promise<void> = Promise.resolve();
  private closed: boolean = false;

  constructor(ep: Endpoint) {
    this.ep = ep;
  }

  async open(net: connection.NetHandle | undefined): Promise<void> {
    const v6 = this.ep.host.includes(':');
    if (net !== undefined && !v6) {
      try {
        await this.tcp.bind({ address: '0.0.0.0', port: 0, family: 1 });
        await net.bindSocket(this.tcp);
      } catch (e) {
        // 绑不上就走默认路由
      }
    }
    this.tcp.on('message', (m: socket.SocketMessageInfo) => this.push(new Uint8Array(m.message)));
    this.tcp.on('close', () => this.fail('车机断开了连接'));
    this.tcp.on('error', (e: BusinessError) => this.fail('连接出错：' + reason(e)));
    try {
      await this.tcp.connect({ address: { address: this.ep.host, port: this.ep.port, family: v6 ? 2 : 1 }, timeout: 5000 });
    } catch (e) {
      this.fail(`连不上 ${this.ep.toString()}：${reason(e)}`);
      return;
    }
    try {
      await this.tcp.setExtraOptions({ TCPNoDelay: true, keepAlive: true });
    } catch (e) {
      // 只是优化
    }
  }

  /**
   * 按调用顺序一帧一帧发，每次都等上一帧发完（加密计数器要和发送顺序一致）。
   * 返回这一帧发完（或连接失败）的时刻。data 必须是长度正好的数组：发的是整个底层 ArrayBuffer。
   */
  send(data: Uint8Array): Promise<void> {
    if (this.closed) {
      return Promise.resolve();
    }
    this.inflight++;
    const buf = data.byteOffset === 0 && data.byteLength === data.buffer.byteLength ? data : data.slice();
    this.chain = this.chain.then((): Promise<void> => this.tcp.send({ data: buf.buffer as ArrayBuffer })).then(() => {
      this.inflight--;
    }, (e: BusinessError) => {
      this.inflight--;
      this.fail('发送失败：' + reason(e));
    });
    return this.chain;
  }

  fail(why: string): void {
    if (this.closed) {
      return;
    }
    const report = this.onError;
    this.close();
    report(why);
  }

  /** 主动关闭（不回调 onError）：先发完已经交给 send 的帧，比如 BYE。 */
  close(): void {
    this.closed = true;
    this.onFrame = (f: Frame) => {
    };
    this.onError = (r: string) => {
    };
    this.chain.then((): Promise<void> => this.tcp.close()).catch(() => {
    });
  }

  private push(chunk: Uint8Array): void {
    this.buf = this.buf.length > 0 ? cat([this.buf, chunk]) : chunk;
    while (!this.closed && this.buf.length >= 5) {
      const b = this.buf;
      const len = ((b[1] << 24) | (b[2] << 16) | (b[3] << 8) | b[4]) >>> 0;
      if (len > this.max) {
        this.fail(`帧长度异常：${len}`);
        return;
      }
      if (b.length < 5 + len) {
        return;
      }
      this.buf = b.subarray(5 + len);
      this.onFrame(new Frame(b[0], b.slice(5, 5 + len)));
    }
  }
}
