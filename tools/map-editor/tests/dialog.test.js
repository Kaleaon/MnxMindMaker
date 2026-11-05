import test from 'node:test';
import assert from 'node:assert/strict';

class MockElement {
  constructor(tagName = 'div', attrs = {}) {
    this.tagName = tagName.toUpperCase();
    this.attributes = new Map(Object.entries(attrs));
    this.listeners = new Map();
    this.children = [];
    this.id = attrs.id || '';
    this.focused = false;
    this.modalOpen = false;
  }

  hasAttribute(name) {
    return this.attributes.has(name);
  }

  getAttribute(name) {
    return this.attributes.get(name);
  }

  setAttribute(name, val) {
    this.attributes.set(name, val);
  }

  addEventListener(event, fn) {
    if (!this.listeners.has(event)) this.listeners.set(event, []);
    this.listeners.get(event).push(fn);
  }

  dispatchEvent(event) {
    const type = typeof event === 'string' ? event : event.type;
    const callbacks = this.listeners.get(type) || [];
    for (const fn of callbacks) fn(event);
  }

  querySelector(selector) {
    const tags = selector.split(',').map(s => s.trim().toUpperCase());
    for (const child of this.children) {
      if (tags.includes(child.tagName)) return child;
      const found = child.querySelector(selector);
      if (found) return found;
    }
    return null;
  }

  showModal() {
    this.modalOpen = true;
  }

  close() {
    this.modalOpen = false;
    this.dispatchEvent('close');
  }

  focus() {
    this.focused = true;
  }
}

globalThis.document = globalThis.document || {
  elements: new Map(),
  getElementById(id) {
    return this.elements.get(id) || null;
  },
  querySelectorAll() { return []; }
};
globalThis.window = globalThis.window || {};

import { ModalDialogController } from '../dist/app.js';

test('ModalDialogController constructor resolves string ID or element', () => {
  const dialogElem = new MockElement('dialog', { id: 'test-dialog' });
  globalThis.document.elements = globalThis.document.elements || new Map();
  globalThis.document.elements.set('test-dialog', dialogElem);

  const controllerById = new ModalDialogController('test-dialog');
  assert.equal(controllerById.dialog, dialogElem);

  const controllerByElem = new ModalDialogController(dialogElem);
  assert.equal(controllerByElem.dialog, dialogElem);
});

test('ModalDialogController _ensureAccessibleName links heading automatically', () => {
  const dialog = new MockElement('dialog');
  const heading = new MockElement('h2');
  dialog.children.push(heading);

  new ModalDialogController(dialog);

  assert.ok(dialog.hasAttribute('aria-labelledby'));
  const headingId = heading.id;
  assert.ok(headingId.startsWith('dialog-heading-'));
  assert.equal(dialog.getAttribute('aria-labelledby'), headingId);
});

test('ModalDialogController _ensureAccessibleName preserves existing aria-labelledby', () => {
  const dialog = new MockElement('dialog', { 'aria-labelledby': 'custom-heading' });
  const heading = new MockElement('h2', { id: 'custom-heading' });
  dialog.children.push(heading);

  new ModalDialogController(dialog);

  assert.equal(dialog.getAttribute('aria-labelledby'), 'custom-heading');
});

test('ModalDialogController open and close manage state and restore focus', () => {
  const dialog = new MockElement('dialog', { 'aria-labelledby': 'existing' });
  const trigger = new MockElement('button');

  const controller = new ModalDialogController(dialog);

  controller.open(trigger);
  assert.equal(controller.triggerElement, trigger);
  assert.equal(dialog.modalOpen, true);

  controller.close();
  assert.equal(dialog.modalOpen, false);
  assert.equal(trigger.focused, true);
  assert.equal(controller.triggerElement, null);
});
