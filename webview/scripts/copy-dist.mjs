import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const cwd = process.cwd();
const targets = [
  ['dist/index.html', '../src/main/resources/html/claude-chat.html'],
  ['dist/office/office.html', '../src/main/resources/html/pet-office.html'],
];

const main = async () => {
  for (const [source, target] of targets) {
    if (process.argv.includes('--office-only') && source === 'dist/index.html') continue;
    const distFile = path.resolve(cwd, source);
    const targetFile = path.resolve(cwd, target);
    const html = await readFile(distFile, 'utf-8');
    await mkdir(path.dirname(targetFile), { recursive: true });
    await writeFile(targetFile, html, 'utf-8');
    console.log(`[copy-dist] Synced ${distFile} -> ${targetFile}`);
  }
};

main().catch((error) => {
  console.error('[copy-dist] 复制构建产物失败', error);
  process.exit(1);
});

