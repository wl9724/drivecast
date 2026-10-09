import { common, wantAgent } from '@kit.AbilityKit';
import { ErrorEvent, MessageEvents, worker } from '@kit.ArkTS';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { mdns } from '@kit.NetworkKit';
import { Conn, wifiNet } from '../core/Conn';
import { PairingClient, newKey, random, rawPublic, usedCodes } from '../core/Crypto';
import { Endpoint, Found, Frame, FromWorker, MAGIC, SERVICE_TYPE, ToWorker, text, toHex } from '../core/Proto';
import { runSelfTest } from '../core/SelfTest';
import { PairedCar, Store } from '../core/Store';

/** mDNS 找到的车机 */
class Car {
  name: string = '';
  carId: string = '';
  host: string = '';
  port: number = 0;
}

const NOTES: string[] = [
  '每次点「开始投屏」系统都会弹窗确认录屏，请在手机上点允许，等车机出现画面后再切到导航。车机断开重连不用再确认。',
  '请保持手机屏幕常亮、导航 App 在前台：锁屏或熄屏可能会中断投屏。',
  '声音请让手机通过蓝牙连接车机播放。',
  '车机上只能看，不能操作手机（鸿蒙不允许第三方 App 模拟点击）。',
  '密码框、支付页面等隐私窗口在车机上会被遮挡。',
  '手机和车机要连在同一个 Wi-Fi 或热点上。',
];

const BLUE = '#0A59F7';
const RED = '#E84026';
const GRAY = '#8C8C8C';

@Entry
@Component
struct Index {
  @State found: Car[] = [];
  @State paired: PairedCar[] = [];
  @State casting: boolean = false;
  @State status: string = '';
  @State manual: string = '';
  @State deviceName: string = '';
  @State phase: string = ''; // 配对：'' / connecting / code / working / done / failed
  @State phaseText: string = '';
  @State code: string = '';
  @State codeError: string = '';
  @State testLog: string = '';
  private ctx: common.UIAbilityContext = AppStorage.get<common.UIAbilityContext>('context') as common.UIAbilityContext;
  private store: Store = new Store(AppStorage.get<common.UIAbilityContext>('context') as common.UIAbilityContext);
  private ds: mdns.DiscoveryService | null = null;
  private caster: worker.ThreadWorker | null = null;
  private conn: Conn | null = null;
  private client: PairingClient | null = null;
  private carName: string = '';
  private resolving: Promise<void> = Promise.resolve();

  aboutToAppear(): void {
    this.paired = this.store.cars();
    this.manual = this.store.manual;
    this.deviceName = this.store.deviceName;
    this.discover();
  }

  aboutToDisappear(): void {
    this.ds?.stopSearchingMDNS();
    this.stopCasting(); // 页面没了就没人能点停止
  }

  build() {
    Stack() {
      Scroll() {
        Column({ space: 10 }) {
          Text('DriveCast').fontSize(28).fontWeight(FontWeight.Bold).width('100%')
          Button(this.casting ? '停止投屏' : '开始投屏')
            .width('100%')
            .height(64)
            .fontSize(22)
            .backgroundColor(this.casting ? RED : BLUE)
            .onClick(() => {
              this.toggle();
            })
          if (this.status.length > 0) {
            Text(this.status).fontSize(15).width('100%')
          }

          this.section('使用前（请停车时设置）')
          ForEach(NOTES, (n: string) => {
            Text('· ' + n).fontSize(14).width('100%')
          }, (n: string) => n)

          this.section('附近的车机')
          if (this.found.length === 0) {
            Text('正在查找…').fontSize(14).fontColor(GRAY).width('100%')
          }
          ForEach(this.found, (car: Car) => {
            Row() {
              Text(car.name).fontSize(16).layoutWeight(1)
              Text(this.isPaired(car.carId) ? '已配对，重新配对' : '配对').fontSize(14).fontColor(BLUE)
            }
            .width('100%')
            .height(48)
            .onClick(() => {
              this.pair(new Endpoint(car.host, car.port), car.name);
            })
          }, (car: Car) => car.name + car.host)
          Text('先在车机的 DriveCast 里点「添加 iPhone/鸿蒙」，再点这里的车机，然后输入车机上显示的配对码。')
            .fontSize(13).fontColor(GRAY).width('100%')

          this.section('车机地址')
          TextInput({ placeholder: '例如 192.168.43.1', text: this.manual })
            .onChange((v: string) => {
              this.manual = v;
              this.store.manual = v;
            })
          Button('用这个地址配对')
            .enabled(Endpoint.parse(this.manual) !== null)
            .onClick(() => {
              const ep = Endpoint.parse(this.manual);
              if (ep !== null) {
                this.pair(ep, ep.toString());
              }
            })
          Text('上面找不到车机时手动输入，地址显示在车机的 DriveCast 里。投屏时找不到车机也会试这个地址。')
            .fontSize(13).fontColor(GRAY).width('100%')

          this.section('这台手机在车机上显示的名字')
          TextInput({ text: this.deviceName })
            .onChange((v: string) => {
              this.deviceName = v;
              this.store.deviceName = v;
            })

          if (this.paired.length > 0) {
            this.section('已配对的车机')
            ForEach(this.paired, (c: PairedCar) => {
              Row() {
                Text(c.name).fontSize(16).layoutWeight(1)
                Button('删除').fontSize(14).height(32).backgroundColor(RED)
                  .onClick(() => {
                    this.forget(c.id);
                  })
              }.width('100%')
            }, (c: PairedCar) => c.id)
          }

          this.section('关于')
          Button('协议自检').onClick(() => {
            this.selfTest();
          })
          if (this.testLog.length > 0) {
            Text(this.testLog).fontSize(12).width('100%')
          }
        }
        .padding(16)
        .width('100%')
      }
      .width('100%')
      .height('100%')

      if (this.phase.length > 0) {
        this.pairPanel()
      }
    }
  }

  @Builder
  section(title: string) {
    Text(title).fontSize(14).fontColor(GRAY).width('100%').margin({ top: 14 })
  }

  @Builder
  pairPanel() {
    Column({ space: 16 }) {
      Text('配对').fontSize(22).fontWeight(FontWeight.Bold)
      if (this.phase === 'code') {
        TextInput({ placeholder: '6 位配对码', text: this.code })
          .type(InputType.Number)
          .maxLength(6)
          .onChange((v: string) => {
            this.code = v;
          })
        Text(this.codeError.length > 0 ? this.codeError : '输入车机屏幕上显示的配对码。')
          .fontColor(this.codeError.length > 0 ? RED : GRAY)
        Button('确定').width('100%').onClick(() => {
          this.submit();
        })
      } else {
        Text(this.phaseText).fontSize(16).fontColor(this.phase === 'failed' ? RED : Color.Black)
      }
      if (this.phase === 'failed') {
        Text('请在车机上重新点「添加 iPhone/鸿蒙」后再试。').fontColor(GRAY)
      }
      Button(this.phase === 'done' ? '完成' : '取消').width('100%').onClick(() => {
        this.cancelPair();
      })
    }
    .padding(24)
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .backgroundColor(Color.White)
  }

  private isPaired(carId: string): boolean {
    return this.paired.some((p: PairedCar) => p.id === carId);
  }

  /** App 一打开就用 mDNS 找车机；投屏时找到的已配对车机也交给投屏 Worker。 */
  private discover(): void {
    try {
      const ds = mdns.createDiscoveryService(this.ctx, SERVICE_TYPE);
      ds.on('serviceFound', (s: mdns.LocalServiceInfo) => {
        // 同时解析同一个服务会报 2204003，排队一个一个来
        this.resolving = this.resolving.then(() => mdns.resolveLocalService(this.ctx, s))
          .then((r: mdns.LocalServiceInfo) => this.addCar(r))
          .catch((e: BusinessError) => {
          });
      });
      ds.on('serviceLost', (s: mdns.LocalServiceInfo) => {
        this.found = this.found.filter((c: Car) => c.name !== s.serviceName);
        this.pushFound();
      });
      ds.startSearchingMDNS();
      this.ds = ds;
    } catch (e) {
      this.status = '无法查找车机：' + (e as BusinessError).message;
    }
  }

  private addCar(r: mdns.LocalServiceInfo): void {
    const host = r.host?.address ?? '';
    const port = r.port ?? 0;
    if (host.length === 0 || host.includes(':') || port === 0) {
      return; // 只用 IPv4：socket 的地址没有 IPv6 的 scope id
    }
    const attrs: mdns.ServiceAttribute[] = r.serviceAttribute ?? [];
    const id = attrs.find((a: mdns.ServiceAttribute) => a.key === 'id');
    const car = new Car();
    car.name = r.serviceName;
    car.carId = id !== undefined ? String.fromCharCode(...id.value).toLowerCase() : '';
    car.host = host;
    car.port = port;
    this.found = this.found.filter((c: Car) => c.name !== car.name).concat([car])
      .sort((a: Car, b: Car) => a.name.localeCompare(b.name));
    this.pushFound();
  }

  private foundPaired(): Found[] {
    return this.found.filter((c: Car) => this.isPaired(c.carId)).map((c: Car): Found => {
      const f: Found = { carId: c.carId, host: c.host, port: c.port };
      return f;
    });
  }

  private pushFound(): void {
    if (this.casting && this.caster !== null) {
      const m: ToWorker = { cmd: 'found', found: this.foundPaired() };
      this.caster.postMessage(m);
    }
  }

  /** 录屏必须由用户在前台点按钮开始：系统会弹隐私确认框。 */
  private async toggle(): Promise<void> {
    if (this.casting) {
      this.stopCasting();
      return;
    }
    const phoneId = this.store.phoneId(false);
    const cars = this.store.carKeys();
    if (phoneId === null || cars.length === 0) {
      this.status = '还没有和车机配对。请先在车机上点「添加 iPhone/鸿蒙」，再在下面配对';
      return;
    }
    this.casting = true;
    this.status = '正在找车机…';
    try {
      await this.keepAlive(true);
    } catch (e) {
      this.status = '无法申请后台运行，切到其他 App 后投屏可能会中断：' + (e as BusinessError).message;
    }
    const m: ToWorker = {
      cmd: 'start', phoneId: toHex(phoneId), cars: cars, last: this.store.addresses(), manual: this.manual,
      // 只配对了一台车机时上次的尺寸才可靠：多台车机尺寸不同，提前按旧尺寸录屏会在连上后重开、多弹一次确认框
      found: this.foundPaired(), hello: cars.length === 1 ? this.store.hello : ''
    };
    this.casterWorker().postMessage(m);
  }

  private casterWorker(): worker.ThreadWorker {
    const old = this.caster;
    if (old !== null) {
      return old;
    }
    const w = new worker.ThreadWorker('entry/ets/workers/Caster.ets');
    w.onmessage = (e: MessageEvents) => this.fromWorker(e.data as FromWorker);
    w.onerror = (e: ErrorEvent) => {
      console.error('DriveCast worker: ' + e.message);
      this.stopCasting(); // 停录屏、断开车机，Worker 回 stopped
    };
    this.caster = w;
    return w;
  }

  private fromWorker(m: FromWorker): void {
    if (m.type === 'status' && this.casting) {
      this.status = m.text;
    } else if (m.type === 'good') {
      this.store.remember(m.text);
      this.store.hello = m.hello ?? '';
    } else if (m.type === 'stopped') {
      this.casting = false;
      this.status = m.text;
      this.keepAlive(false);
    }
  }

  /** Worker 停好后回 stopped，那时再停长时任务。 */
  private stopCasting(): void {
    const m: ToWorker = { cmd: 'stop' };
    this.caster?.postMessage(m);
  }

  /** 长时任务（录制类）：切到导航 App 后进程不被挂起。 */
  private async keepAlive(on: boolean): Promise<void> {
    if (!on) {
      try {
        await backgroundTaskManager.stopBackgroundRunning(this.ctx);
      } catch (e) {
        // 本来就没在跑
      }
      return;
    }
    const info: wantAgent.WantAgentInfo = {
      wants: [{ bundleName: this.ctx.abilityInfo.bundleName, abilityName: this.ctx.abilityInfo.name }],
      actionType: wantAgent.OperationType.START_ABILITY,
      requestCode: 0,
      actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG]
    };
    const agent = await wantAgent.getWantAgent(info);
    await backgroundTaskManager.startBackgroundRunning(this.ctx, backgroundTaskManager.BackgroundMode.AUDIO_RECORDING,
      agent);
  }

  /** 车机上要先点"添加 iPhone/鸿蒙"（车机只在配对模式下接受配对）。 */
  private pair(ep: Endpoint, carName: string): void {
    this.cancelPair();
    let phoneId: Uint8Array | null = null;
    try {
      phoneId = this.store.phoneId(true);
    } catch (e) {
      phoneId = null;
    }
    if (phoneId === null) {
      this.phase = 'failed';
      this.phaseText = '关键资产存储不可用，无法保存配对信息';
      return;
    }
    const kp = newKey();
    const client = new PairingClient(phoneId, this.deviceName.length > 0 ? this.deviceName : '鸿蒙手机', kp.priKey,
      rawPublic(kp.pubKey), random, usedCodes);
    const conn = new Conn(ep);
    this.client = client;
    this.conn = conn;
    this.carName = carName;
    this.phase = 'connecting';
    this.phaseText = '正在连接车机…';
    this.code = '';
    this.codeError = '';
    conn.onError = (r: string) => {
      if (this.conn === conn) {
        this.conn = null;
        this.phase = 'failed';
        this.phaseText = r;
      }
    };
    conn.onFrame = (f: Frame) => this.pairFrame(conn, client, f);
    conn.open(wifiNet()).then(() => conn.send(MAGIC));
  }

  private pairFrame(conn: Conn, client: PairingClient, f: Frame): void {
    if (this.conn !== conn) {
      return;
    }
    try {
      const o = client.receive(f);
      if (o.send !== null) {
        conn.send(o.send);
      } else if (o.needCode) {
        this.phase = 'code'; // 等输入时车机也可能超时发 BYE
      } else if (o.paired) {
        this.conn = null;
        conn.close();
        this.saved(conn, client);
      }
    } catch (e) {
      conn.fail((e as Error).message);
    }
  }

  private saved(conn: Conn, client: PairingClient): void {
    try {
      this.store.savePairing(toHex(client.carId), client.ltk, this.carName);
    } catch (e) {
      this.phase = 'failed';
      this.phaseText = '保存配对信息失败：' + (e as BusinessError).message;
      return;
    }
    // 记下地址：mDNS 不通时（如车机连手机热点）投屏也能直接连
    this.store.remember(conn.ep.toString());
    this.paired = this.store.cars();
    this.phase = 'done';
    this.phaseText = `已和「${this.carName}」配对。以后点「开始投屏」就会自动连接这台车机。`;
  }

  /** 用户输入车机上显示的 6 位配对码。 */
  private submit(): void {
    const conn = this.conn;
    const client = this.client;
    if (conn === null || client === null) {
      return;
    }
    if (this.code.length !== 6 || !this.code.split('').every((ch: string) => ch >= '0' && ch <= '9')) {
      this.codeError = '请输入车机上显示的 6 位数字';
      return;
    }
    try {
      conn.send(client.submit(Number(this.code)));
      this.phase = 'working';
      this.phaseText = '正在配对…';
      this.codeError = '';
    } catch (e) {
      this.codeError = (e as Error).message;
    }
  }

  private cancelPair(): void {
    this.conn?.close();
    this.conn = null;
    this.client = null;
    this.phase = '';
  }

  private forget(carIdHex: string): void {
    this.store.forget(carIdHex);
    this.paired = this.store.cars();
  }

  /** 用系统密码库跑一遍 docs/testvectors/ios-pairing.json（打包在 rawfile 里）。 */
  private selfTest(): void {
    try {
      const raw = this.ctx.resourceManager.getRawFileContentSync('ios-pairing.json');
      this.testLog = runSelfTest(text(raw)).join('\n') + '\n全部通过';
    } catch (e) {
      this.testLog = '自检失败：' + (e as Error).message;
    }
  }
}
