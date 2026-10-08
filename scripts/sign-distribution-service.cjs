const fs = require('node:fs');
const path = require('node:path');
const { signService, signPayload } = require('../desktop/native-signing.cjs');
const [root, output, mode] = process.argv.slice(2);
if (!root || !output) throw new Error('Pass staged production service directory and receipt output');
const action = mode === 'READERS' ? signPayload(path.resolve(root)).then(() => ({ schema: 'pathlab.forge.reader-payload-signature/1', result: 'PASS' })) : signService(path.resolve(root));
action.then(receipt => {
  fs.mkdirSync(path.dirname(path.resolve(output)), { recursive: true });
  fs.writeFileSync(output, JSON.stringify(receipt, null, 2) + '\n');
}).catch(() => { console.error('Native service signing failed; no release receipt issued.'); process.exitCode = 1; });
