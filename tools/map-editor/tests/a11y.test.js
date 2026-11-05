import test from 'node:test';
import assert from 'node:assert/strict';

test('say updates ARIA attributes and sets aria-invalid/aria-describedby on error targetInput', () => {
  const messageElement = {
    textContent: '',
    hidden: true,
    classList: {
      toggle(cls, val) {
        this[cls] = !!val;
      }
    },
    attributes: {},
    setAttribute(key, val) {
      this.attributes[key] = String(val);
    },
    getAttribute(key) {
      return this.attributes[key];
    }
  };

  const elements = { message: messageElement };

  const inputListeners = [];
  const targetInput = {
    attributes: {},
    setAttribute(key, val) {
      this.attributes[key] = String(val);
    },
    getAttribute(key) {
      return this.attributes[key];
    },
    removeAttribute(key) {
      delete this.attributes[key];
    },
    addEventListener(event, listener, options) {
      inputListeners.push({ event, listener, options });
    },
    emit(event) {
      for (let i = 0; i < inputListeners.length; i++) {
        if (inputListeners[i].event === event) {
          inputListeners[i].listener();
          if (inputListeners[i].options?.once) {
            inputListeners.splice(i, 1);
            i--;
          }
        }
      }
    }
  };

  const $ = id => elements[id];

  function say(message, error = false, permanent = false, targetInput = null) {
    const msgEl = $('message');
    msgEl.textContent = message; msgEl.classList.toggle('error', error);
    if (error) {
      msgEl.setAttribute('role', 'alert');
      msgEl.setAttribute('aria-live', 'assertive');
    } else {
      msgEl.setAttribute('role', 'status');
      msgEl.setAttribute('aria-live', 'polite');
    }
    msgEl.hidden = false;
    if (targetInput && error) {
      targetInput.setAttribute('aria-invalid', 'true');
      targetInput.setAttribute('aria-describedby', 'message');
      targetInput.addEventListener('input', () => {
        targetInput.removeAttribute('aria-invalid');
        targetInput.removeAttribute('aria-describedby');
      }, { once: true });
    }
  }

  // 1. Non-error notification sets role="status" and aria-live="polite"
  say('Status update');
  assert.equal(messageElement.getAttribute('role'), 'status');
  assert.equal(messageElement.getAttribute('aria-live'), 'polite');
  assert.equal(messageElement.textContent, 'Status update');

  // 2. Error notification sets role="alert" and aria-live="assertive", plus input error attributes
  say('Validation failed', true, false, targetInput);
  assert.equal(messageElement.getAttribute('role'), 'alert');
  assert.equal(messageElement.getAttribute('aria-live'), 'assertive');
  assert.equal(targetInput.getAttribute('aria-invalid'), 'true');
  assert.equal(targetInput.getAttribute('aria-describedby'), 'message');

  // 3. User input clears aria-invalid and aria-describedby
  targetInput.emit('input');
  assert.equal(targetInput.getAttribute('aria-invalid'), undefined);
  assert.equal(targetInput.getAttribute('aria-describedby'), undefined);
});
