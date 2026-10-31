import type { OfficeThemeId } from './officeThemes';

interface NameplatePalette { primary: string; secondary: string; ink: string; glass: string }
const PALETTES: Record<OfficeThemeId, NameplatePalette> = {
  loft: { primary: '#ffd48a', secondary: '#ff9d78', ink: '#fff5dd', glass: '#382722' },
  cyberpunk: { primary: '#67efff', secondary: '#f584ff', ink: '#eaffff', glass: '#111b32' },
  cultivation: { primary: '#ffe1a0', secondary: '#83f0ca', ink: '#fff5d7', glass: '#193e36' },
  space: { primary: '#9fe5ff', secondary: '#bca2ff', ink: '#f0f8ff', glass: '#152b4b' },
  forest: { primary: '#c6f08c', secondary: '#72e6b6', ink: '#f3ffe4', glass: '#203d29' },
  library: { primary: '#f8d49b', secondary: '#ddadff', ink: '#fff4df', glass: '#352842' },
  ocean: { primary: '#86f8ec', secondary: '#7fbaff', ink: '#e8ffff', glass: '#12394d' },
  zen: { primary: '#ffd4e5', secondary: '#d2e4a4', ink: '#fff2f6', glass: '#3c343c' },
  retro: { primary: '#ffd48b', secondary: '#ff977b', ink: '#fff1c9', glass: '#38272f' },
};
const TAU = Math.PI * 2;
const layouts = new Map<string, { text: string; width: number }>();

/** Keep measurements out of the animation loop, with a bounded cache across floors/themes. */
function layout(ctx: CanvasRenderingContext2D, name: string, maxWidth: number) {
  const key = `${ctx.font}/${maxWidth}/${name}`;
  const cached = layouts.get(key);
  if (cached) return cached;
  const characters = Array.from(name);
  let text = name;
  if (ctx.measureText(text).width > maxWidth - 40) {
    let low = 0, high = characters.length;
    while (low < high) {
      const middle = Math.ceil((low + high) / 2);
      if (ctx.measureText(characters.slice(0, middle).join('') + '…').width <= maxWidth - 40) low = middle;
      else high = middle - 1;
    }
    text = characters.slice(0, low).join('') + '…';
  }
  const result = { text, width: Math.min(maxWidth, Math.max(94, ctx.measureText(text).width + 40)) };
  if (layouts.size >= 256) layouts.clear();
  layouts.set(key, result); return result;
}

function frame(ctx: CanvasRenderingContext2D, width: number): void {
  const half = width / 2;
  ctx.beginPath(); ctx.moveTo(-half + 7, -17); ctx.lineTo(half - 7, -17); ctx.lineTo(half, -10);
  ctx.lineTo(half, 10); ctx.lineTo(half - 7, 17); ctx.lineTo(-half + 7, 17);
  ctx.lineTo(-half, 10); ctx.lineTo(-half, -10); ctx.closePath();
}

function star(ctx: CanvasRenderingContext2D, x: number, y: number, radius: number): void {
  ctx.beginPath(); ctx.moveTo(x - radius, y); ctx.lineTo(x - 1, y - 1); ctx.lineTo(x, y - radius);
  ctx.lineTo(x + 1, y - 1); ctx.lineTo(x + radius, y); ctx.lineTo(x + 1, y + 1);
  ctx.lineTo(x, y + radius); ctx.lineTo(x - 1, y + 1); ctx.closePath(); ctx.fill();
}

/** Small, deterministic ornaments use the room's existing clock; no timers or particle allocations. */
function ornaments(ctx: CanvasRenderingContext2D, theme: OfficeThemeId, width: number, t: number, palette: NameplatePalette): void {
  const half = width / 2;
  ctx.strokeStyle = palette.primary; ctx.fillStyle = palette.primary; ctx.lineWidth = 1;
  if (theme === 'space' || theme === 'cultivation') {
    ctx.globalAlpha = .6; ctx.save(); ctx.rotate(Math.sin(t / 4) * .08);
    ctx.beginPath(); ctx.ellipse(0, 0, half + 10, 24, -.12, Math.PI * .07, Math.PI * .93); ctx.stroke();
    ctx.beginPath(); ctx.ellipse(0, 0, half + 10, 24, -.12, Math.PI * 1.07, Math.PI * 1.93); ctx.stroke();
    const angle = t * .8;
    const x = Math.cos(angle) * (half + 10), y = Math.sin(angle) * 24;
    if (theme === 'space') { star(ctx, x, y, 4); ctx.fillStyle = palette.secondary; star(ctx, -x, -y, 3); }
    else {
      for (const side of [-1, 1]) {
        ctx.save(); ctx.translate(side * (half + 8), 0); ctx.rotate(t / 3); ctx.strokeRect(-4, -4, 8, 8); ctx.restore();
      }
    }
    ctx.restore();
  }
  if (theme === 'ocean') {
    ctx.globalAlpha = .65; ctx.beginPath();
    for (let i = 0; i <= 28; i++) {
      const x = -half + i * width / 28, y = 21 + Math.sin(i / 2.7 + t * 1.8) * 2;
      if (!i) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.stroke();
  } else if (theme === 'library') {
    ctx.globalAlpha = .6; ctx.strokeStyle = palette.secondary;
    for (const side of [-1, 1]) {
      ctx.beginPath(); ctx.moveTo(side * (half - 15), 22); ctx.lineTo(side * (half - 4), 22);
      ctx.quadraticCurveTo(side * (half + 9), 10, side * (half - 1), -2); ctx.stroke();
    }
  }
  for (let i = 0; i < 6; i++) {
    const phase = t * (.25 + i * .018) + i * 1.618;
    const x = (i / 5 - .5) * (width + 10) + Math.sin(phase) * 4;
    const y = (i % 2 ? -1 : 1) * (21 + Math.sin(phase * 1.3) * 3);
    ctx.globalAlpha = .35 + (Math.sin(phase * 2) + 1) * .25;
    ctx.fillStyle = i % 2 ? palette.secondary : palette.primary;
    ctx.strokeStyle = ctx.fillStyle;
    if (theme === 'cyberpunk' || theme === 'retro') {
      ctx.fillRect(x - 2, y, theme === 'retro' ? 4 : 8, 2);
      if (theme === 'cyberpunk') { ctx.beginPath(); ctx.moveTo(x - 5, y - 4); ctx.lineTo(x - 1, y - 4); ctx.lineTo(x + 2, y - 7); ctx.stroke(); }
    } else if (theme === 'forest' || theme === 'zen') {
      ctx.beginPath(); ctx.ellipse(x, y, theme === 'zen' ? 3.2 : 4, 1.6, phase, 0, TAU); ctx.fill();
    } else if (theme === 'ocean') {
      ctx.beginPath(); ctx.arc(x, y, 1.8 + i % 3, 0, TAU); ctx.stroke();
    } else if (theme === 'cultivation') {
      ctx.save(); ctx.translate(x, y); ctx.rotate(Math.PI / 4); ctx.strokeRect(-2, -2, 4, 4); ctx.restore();
    } else star(ctx, x, y, i % 2 ? 3 : 4.5);
  }
}

/** Theme-specific head-up nameplate, independent of Pet opacity and clipped to its own text frame. */
export function drawOfficeNameplate(ctx: CanvasRenderingContext2D, name: string, x: number, y: number, scale: number,
  maxWidth: number, theme: OfficeThemeId, time: number, animated: boolean, seed: number, selected: boolean): void {
  if (!name) return;
  const palette = PALETTES[theme], t = animated ? time / 1000 + seed * .73 : 0;
  ctx.save(); ctx.translate(x, y); ctx.scale(scale, scale);
  ctx.font = `800 19px ${theme === 'retro' ? '"Consolas", ' : ''}"Segoe UI", "Microsoft YaHei", sans-serif`;
  const { text, width } = layout(ctx, name, maxWidth);
  const half = width / 2;
  ornaments(ctx, theme, width, t, palette);
  ctx.globalAlpha = 1;
  const border = ctx.createLinearGradient(-half, -17, half, 17);
  border.addColorStop(0, palette.primary); border.addColorStop(.5, palette.secondary); border.addColorStop(1, palette.primary);
  const glass = ctx.createLinearGradient(0, -17, 0, 17);
  glass.addColorStop(0, `${palette.glass}f5`); glass.addColorStop(1, `${palette.glass}e8`);
  ctx.fillStyle = glass; ctx.strokeStyle = border; ctx.lineWidth = selected ? 1.8 : 1.2;
  ctx.shadowColor = palette.primary; ctx.shadowBlur = animated ? (selected ? 13 : 8) : 3;
  frame(ctx, width); ctx.fill(); ctx.stroke(); ctx.shadowBlur = 0;
  ctx.save(); frame(ctx, width); ctx.clip();
  if (animated) {
    const sweep = ((t / 4) % 1) * (width + 90) - half - 45;
    const light = ctx.createLinearGradient(sweep - 24, 0, sweep + 24, 0);
    light.addColorStop(0, `${palette.primary}00`); light.addColorStop(.5, `${palette.primary}38`); light.addColorStop(1, `${palette.primary}00`);
    ctx.fillStyle = light; ctx.fillRect(-half, -17, width, 34);
    if (theme === 'cyberpunk') {
      ctx.fillStyle = `${palette.primary}12`;
      for (let row = -16; row < 17; row += 4) ctx.fillRect(-half, row + (t % 1), width, 1);
    }
  }
  const ink = ctx.createLinearGradient(0, -10, 0, 11);
  ink.addColorStop(0, palette.ink); ink.addColorStop(.6, palette.ink); ink.addColorStop(1, palette.primary);
  ctx.fillStyle = ink; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
  ctx.shadowColor = palette.primary; ctx.shadowBlur = animated ? 5 : 0;
  ctx.fillText(text, 0, 1); ctx.restore();
  ctx.fillStyle = palette.primary; ctx.globalAlpha = .9;
  for (const side of [-1, 1]) {
    ctx.beginPath(); ctx.moveTo(side * (half - 11), -3); ctx.lineTo(side * (half - 7), 0); ctx.lineTo(side * (half - 11), 3); ctx.closePath(); ctx.fill();
  }
  ctx.fillStyle = palette.secondary; ctx.fillRect(-9, 19, 18, 1.5);
  if (selected) { ctx.fillStyle = palette.primary; ctx.fillRect(-20, -20, 40, 2); }
  ctx.restore();
}
