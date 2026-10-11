/* ============================================================
   tv.js — фокус-менеджер прототипов (имитация пульта)
   Стрелки — пространственная навигация по [data-focusable]
   Enter — активация, Backspace — назад
   Страницы могут перехватить клавишу через window.tvKeyIntercept
   ============================================================ */

(function () {
  'use strict';

  var FOCUSABLE = '[data-focusable]';

  // div/span не фокусируются без tabindex — проставим автоматически
  document.querySelectorAll(FOCUSABLE).forEach(function (el) {
    if (!el.hasAttribute('tabindex')) el.tabIndex = -1;
  });

  function scopeEl() {
    // Если открыт модальный слой — навигация только внутри него
    return document.querySelector('[data-modal-open="true"]') || document;
  }

  function items() {
    var s = scopeEl();
    return Array.prototype.filter.call(
      s.querySelectorAll(FOCUSABLE),
      function (el) {
        return !el.disabled &&
               el.getAttribute('aria-disabled') !== 'true' &&
               el.offsetParent !== null;
      }
    );
  }

  function pick(current, dir) {
    var list = items();
    if (!list.length) return null;
    if (!current || !list.includes(current)) current = list[0];

    var r = current.getBoundingClientRect();
    var cx = r.left + r.width / 2;
    var cy = r.top + r.height / 2;

    var best = null;
    var bestScore = Infinity;
    var EDGE = 8;

    list.forEach(function (el) {
      if (el === current) return;
      var b = el.getBoundingClientRect();
      var ex = b.left + b.width / 2;
      var ey = b.top + b.height / 2;
      var dx = ex - cx, dy = ey - cy;
      var primary, cross;

      if (dir === 'left')  { if (dx >= -EDGE) return; primary = -dx; cross = Math.abs(dy); }
      else if (dir === 'right') { if (dx <= EDGE) return; primary = dx;  cross = Math.abs(dy); }
      else if (dir === 'up')    { if (dy >= -EDGE) return; primary = -dy; cross = Math.abs(dx); }
      else                      { if (dy <= EDGE) return; primary = dy;  cross = Math.abs(dx); }

      var score = primary + cross * 2.5;
      if (score < bestScore) { bestScore = score; best = el; }
    });

    // Нет соседа по направлению —clamp на крайний элемент
    if (!best) best = (dir === 'left' || dir === 'up') ? list[0] : list[list.length - 1];
    return best;
  }

  function focusEl(el) {
    if (!el) return;
    el.focus({ preventScroll: true });
    el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  function isTyping() {
    var t = document.activeElement;
    return !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA');
  }

  document.addEventListener('keydown', function (e) {
    // Страница может обработать клавишу сама
    if (typeof window.tvKeyIntercept === 'function' &&
        window.tvKeyIntercept(e) === true) return;

    var k = e.key;

    if (k === 'ArrowLeft' || k === 'ArrowRight' || k === 'ArrowUp' || k === 'ArrowDown') {
      if (isTyping()) return; // в поле ввода — двигаем курсор
      e.preventDefault();
      var cur = document.activeElement && document.activeElement.matches(FOCUSABLE)
        ? document.activeElement : items()[0];
      focusEl(pick(cur, k.slice(5).toLowerCase()));
      return;
    }

    if (k === 'Enter') {
      var el = document.activeElement;
      if (el && el.matches(FOCUSABLE)) {
        e.preventDefault();
        el.click();
      }
      return;
    }

    if (k === 'Backspace') {
      if (isTyping()) return;
      e.preventDefault();
      history.back();
    }
  });

  // Первичный фокус: видимый [data-focus-default] либо первый доступный
  function initialFocus() {
    var s = scopeEl();
    var el = s.querySelector('[data-focus-default]');
    var visible = el && !el.disabled &&
                  el.getAttribute('aria-disabled') !== 'true' &&
                  el.offsetParent !== null;
    if (!visible) el = items()[0];
    if (el) focusEl(el);
  }

  // Вписываем холст 1920×1080 в окно браузера
  function fitStage() {
    var s = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
    document.body.style.zoom = s < 1 ? s : '';
  }

  window.addEventListener('resize', fitStage);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      initialFocus();
      fitStage();
    });
  } else {
    initialFocus();
    fitStage();
  }

  // Мини-API для страниц
  window.TV = { items: items, pick: pick, focusEl: focusEl };
})();
