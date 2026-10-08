// Keep GitHub Pages' main/docs assets in sync. Preserve site-specific config.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const root = new URL('../../', import.meta.url);
const files = ['index.html', 'style.css', 'app.js', 'drive.js', 'graph.js', 'setup.html'];
const check = process.argv.includes('--check');
for (const name of files) {
  const source = await readFile(new URL('dist/' + name, import.meta.url));
  const target = new URL('docs/' + name, root);
  if (check) {
    if (!source.equals(await readFile(target))) throw new Error('Run npm run sync-docs; docs/' + name + ' differs.');
  } else await writeFile(target, source);
}
const config = new URL('docs/config.js', root);
try { await readFile(config); } catch {
  if (check) throw new Error('Missing docs/config.js');
  await writeFile(config, await readFile(new URL('dist/config.js', import.meta.url)));
}
if (!check) await writeFile(new URL('docs/.nojekyll', root), '');
console.log(check ? 'main/docs assets match editor source' : 'Updated main/docs; existing config.js preserved');
