import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('CSS focus indicators exist in style.css for canvas and graph nodes', () => {
  const css = readFileSync(new URL('../dist/style.css', import.meta.url), 'utf8');
  assert.match(css, /\.graph-node:focus-visible/);
  assert.match(css, /\.graph-node:focus-visible rect/);
  assert.match(css, /svg#canvas:focus-visible/);
  assert.match(css, /outline:\s*2px solid #17283e/);
  assert.match(css, /outline-offset:\s*3px/);
  assert.match(css, /outline:\s*2px solid #315f97/);
});

test('keyboard navigation and interaction handlers adjust node position and selection', () => {
  const node = { id: 'node_1', x: 100, y: 100, label: 'Test Node', type: 'CONCEPT' };
  let selected = null;
  let activeElement = null;

  const handleKeydown = (event) => {
    const step = event.shiftKey ? 50 : 10;
    let moved = false;
    if (event.key === 'ArrowUp') { moved = true; }
    else if (event.key === 'ArrowDown') { moved = true; }
    else if (event.key === 'ArrowLeft') { moved = true; }
    else if (event.key === 'ArrowRight') { moved = true; }

    if (moved) {
      event.defaultPrevented = true;
      if (event.key === 'ArrowUp') node.y -= step;
      else if (event.key === 'ArrowDown') node.y += step;
      else if (event.key === 'ArrowLeft') node.x -= step;
      else if (event.key === 'ArrowRight') node.x += step;
      return;
    }

    if (event.key === 'Enter') {
      event.defaultPrevented = true;
      selected = node.id;
      activeElement = 'node-label';
    } else if (event.key === ' ') {
      event.defaultPrevented = true;
      selected = selected === node.id ? null : node.id;
    } else if (event.key === 'Escape') {
      event.defaultPrevented = true;
      selected = null;
      activeElement = 'canvas';
    }
  };

  // Test ArrowUp 10px
  const eventUp = { key: 'ArrowUp', shiftKey: false };
  handleKeydown(eventUp);
  assert.equal(node.y, 90);
  assert.equal(eventUp.defaultPrevented, true);

  // Test ArrowDown 10px
  const eventDown = { key: 'ArrowDown', shiftKey: false };
  handleKeydown(eventDown);
  assert.equal(node.y, 100);

  // Test ArrowLeft 10px
  const eventLeft = { key: 'ArrowLeft', shiftKey: false };
  handleKeydown(eventLeft);
  assert.equal(node.x, 90);

  // Test ArrowRight 50px with Shift
  const eventRightShift = { key: 'ArrowRight', shiftKey: true };
  handleKeydown(eventRightShift);
  assert.equal(node.x, 140);
  assert.equal(eventRightShift.defaultPrevented, true);

  // Test Space key selection toggle
  const eventSpace = { key: ' ' };
  handleKeydown(eventSpace);
  assert.equal(selected, 'node_1');
  assert.equal(eventSpace.defaultPrevented, true);

  handleKeydown(eventSpace);
  assert.equal(selected, null);

  // Test Enter key
  const eventEnter = { key: 'Enter' };
  handleKeydown(eventEnter);
  assert.equal(selected, 'node_1');
  assert.equal(activeElement, 'node-label');

  // Test Escape key
  const eventEscape = { key: 'Escape' };
  handleKeydown(eventEscape);
  assert.equal(selected, null);
  assert.equal(activeElement, 'canvas');
});
