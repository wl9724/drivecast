import { describe, expect, it } from '@ohos/hypium';
import { application } from '@kit.AbilityKit';
import { abilityDelegatorRegistry } from '@kit.TestKit';
import { text } from '../../../main/ets/core/Proto';
import { runSelfTest } from '../../../main/ets/core/SelfTest';

// 和 App 里的「协议自检」是同一个函数：用系统 cryptoFramework 跑 docs/testvectors/ios-pairing.json 的全部向量
export default function selfTest() {
  describe('DriveCastProtocol', () => {
    it('runsAllVectors', 0, async () => {
      const app = abilityDelegatorRegistry.getAbilityDelegator().getAppContext();
      const entry = await application.createModuleContext(app, 'entry');
      const log = runSelfTest(text(entry.resourceManager.getRawFileContentSync('ios-pairing.json'))); // 有一项不对就抛错
      expect(log.length).assertLarger(20);
    });
  });
}
