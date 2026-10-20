import { test } from 'node:test';
import assert from 'node:assert/strict';
import { arrayAt } from '../src/compat.mjs';

test('older WebView array lookup preserves the native behavior used by Folia', () => {
  for (const values of [[], ['first'], ['first', 'last']]) {
    for (const index of [undefined, NaN, 0, 1, -1, -2, -3, 1.8, -1.8, Infinity, -Infinity]) {
      assert.equal(arrayAt.call(values, index), values.at(index));
    }
  }
  assert.equal(arrayAt.call({0: 'first', 1: 'last', length: 2}, -1), 'last');
  assert.throws(() => arrayAt.call(null, 0), TypeError);
});
