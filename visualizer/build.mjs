import { build } from 'esbuild';
import { mkdir, copyFile, writeFile, readFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const out = '../app/src/main/assets/folia';
await mkdir(out, { recursive: true });
const result = await build({
  entryPoints: ['src/main.ts'], bundle: true, minify: true, format: 'iife',
  target: ['chrome90'], outfile: `${out}/lumiere.js`, legalComments: 'eof', metafile: true,
  // Kinara has no AI theme icons. Avoid bundling React and thousands of dynamic icon imports.
  plugins: [{ name: 'native-theme-icons', setup(build) {
    build.onResolve({ filter: /lucideIconResolver$/ }, () => ({ path: fileURLToPath(new URL('./src/noThemeIcons.ts', import.meta.url)) }));
  } }],
});
await copyFile('src/index.html', `${out}/index.html`);
await copyFile('vendor/folia/LICENSE', `${out}/FOLIA-LICENSE.txt`);
await copyFile('../licenses/GPL-3.0.txt', `${out}/KINARA-LICENSE.txt`);
await writeFile(`${out}/NOTICE.txt`, 'Folia Lumiere © 2026 chthollyphile — AGPL-3.0.\nhttps://github.com/chthollyphile/folia-major\nKinara Android integration © 2026 Kinara contributors.\nCorresponding renderer source and build scripts: visualizer/ in the Kinara source archive.\n');
const packages = new Set(Object.keys(result.metafile.inputs).filter(p => p.startsWith('node_modules/')).map(p => {
  const parts = p.split('/'); return parts.slice(0, parts[1].startsWith('@') ? 3 : 2).join('/');
}));
const notices = [];
for (const root of [...packages].sort()) {
  const pkg = JSON.parse(await readFile(`${root}/package.json`, 'utf8'));
  const files = await readdir(root);
  const license = files.find(n => /^licen[sc]e(?:\.(?:md|txt))?$/i.test(n));
  const licensePath = license ? `${root}/${license}` : pkg.name === '@pixi/colord' ? 'vendor/licenses/colord.txt' : null;
  if (!licensePath) throw new Error(`Missing license text: ${pkg.name}`);
  notices.push(`${pkg.name} ${pkg.version}\n${await readFile(licensePath, 'utf8')}`);
}
await writeFile(`${out}/THIRD-PARTY.txt`, notices.join('\n\n----------------\n\n'));
async function allFiles(root) {
  const files = [];
  for (const entry of await readdir(root, {withFileTypes:true})) {
    const name = `${root}/${entry.name}`;
    files.push(...(entry.isDirectory() ? await allFiles(name) : [name]));
  }
  return files;
}
const inputs = ['build.mjs', 'package.json', 'package-lock.json', ...await allFiles('src'), ...await allFiles('vendor')].sort();
const checksum = createHash('sha256');
for (const path of inputs) checksum.update(path + '\0').update(await readFile(path));
const properties = [`source.sha256=${checksum.digest('hex')}`];
for (const name of (await readdir(out)).sort()) {
  properties.push(`${name}.sha256=${createHash('sha256').update(await readFile(`${out}/${name}`)).digest('hex')}`);
}
await writeFile('bundle.properties', properties.join('\n') + '\n');
console.log('Bundled Folia Lumiere into Android assets.');
