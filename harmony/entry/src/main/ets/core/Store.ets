import { asset } from '@kit.AssetStoreKit';
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { random } from './Crypto';
import { CarKey, toHex, utf8 } from './Proto';

// phoneId 和每台车机的 LTK 放关键资产（Asset Store，TEE 加密，首次解锁后可读，锁屏时投屏也能重连），
// 其余不保密的设置放首选项。只在 UI 线程用。

export class PairedCar {
  id: string = ''; // carId 十六进制
  name: string = '';
}

function query(name: string): asset.AssetMap {
  const q: asset.AssetMap = new Map<asset.Tag, asset.Value>();
  q.set(asset.Tag.ALIAS, utf8('drivecast.' + name));
  return q;
}

function secret(name: string): Uint8Array | null {
  const q = query(name);
  q.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
  try {
    const r = asset.querySync(q);
    return r.length > 0 ? r[0].get(asset.Tag.SECRET) as Uint8Array : null;
  } catch (e) {
    return null; // 24000002：没有
  }
}

function setSecret(name: string, data: Uint8Array): void {
  const m = query(name);
  m.set(asset.Tag.SECRET, data);
  m.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
  m.set(asset.Tag.CONFLICT_RESOLUTION, asset.ConflictResolution.OVERWRITE);
  asset.addSync(m);
}

export class Store {
  private prefs: preferences.Preferences;

  constructor(ctx: common.Context) {
    this.prefs = preferences.getPreferencesSync(ctx, { name: 'drivecast' });
  }

  /** 这台手机的 16 字节随机 ID，第一次配对时生成。 */
  phoneId(create: boolean): Uint8Array | null {
    const id = secret('phoneId');
    if (id !== null || !create) {
      return id;
    }
    const n = random(16);
    setSecret('phoneId', n);
    return n;
  }

  cars(): PairedCar[] {
    return JSON.parse(this.prefs.getSync('cars', '[]') as string) as PairedCar[];
  }

  /** 已配对车机的 LTK，交给投屏 Worker。 */
  carKeys(): CarKey[] {
    const out: CarKey[] = [];
    for (const c of this.cars()) {
      const ltk = secret('car.' + c.id);
      if (ltk !== null) {
        const k: CarKey = { id: c.id, ltk: toHex(ltk) };
        out.push(k);
      }
    }
    return out;
  }

  /** 先存 LTK，成功了才记进车机列表。 */
  savePairing(carIdHex: string, ltk: Uint8Array, name: string): void {
    setSecret('car.' + carIdHex, ltk);
    const car = new PairedCar();
    car.id = carIdHex;
    car.name = name;
    this.setCars(this.cars().filter((c: PairedCar) => c.id !== carIdHex).concat([car]));
    this.put('hello', ''); // 新车机尺寸未知：第一次投屏等它的 HELLO 再录屏，免得按旧尺寸录了又重开、弹两次确认框
  }

  forget(carIdHex: string): void {
    try {
      asset.removeSync(query('car.' + carIdHex));
    } catch (e) {
      // 本来就没有
    }
    this.setCars(this.cars().filter((c: PairedCar) => c.id !== carIdHex));
    this.put('hello', ''); // 删掉的车机的尺寸不能再用于别的车机
  }

  /** 上次连上的地址，最近的在前。 */
  addresses(): string[] {
    return JSON.parse(this.prefs.getSync('addresses', '[]') as string) as string[];
  }

  remember(addr: string): void {
    this.put('addresses', JSON.stringify([addr].concat(this.addresses().filter((a: string) => a !== addr)).slice(0, 4)));
  }

  /** 上次车机 HELLO 的负载（十六进制）：知道车机尺寸，点开始投屏时就能马上录屏（弹确认框）。 */
  get hello(): string {
    return this.prefs.getSync('hello', '') as string;
  }

  set hello(v: string) {
    this.put('hello', v);
  }

  /** 用户手动输入的车机地址，找车的最后一步。 */
  get manual(): string {
    return this.prefs.getSync('manual', '') as string;
  }

  set manual(v: string) {
    this.put('manual', v);
  }

  /** 这台手机在车机上显示的名字。 */
  get deviceName(): string {
    return this.prefs.getSync('deviceName', '我的鸿蒙手机') as string;
  }

  set deviceName(v: string) {
    this.put('deviceName', v);
  }

  private setCars(cars: PairedCar[]): void {
    this.put('cars', JSON.stringify(cars));
  }

  private put(key: string, value: string): void {
    this.prefs.putSync(key, value);
    this.prefs.flush();
  }
}
