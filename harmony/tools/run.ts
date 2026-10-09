import * as fs from 'node:fs';
import { runSelfTest } from './SelfTest.ts';

console.log(runSelfTest(fs.readFileSync(process.argv[2], 'utf8')).join('\n'));
