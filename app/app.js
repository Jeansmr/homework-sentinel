/* ===========================================================
 * 作业哨兵 (Homework Sentinel)
 * -----------------------------------------------------------
 * 一个只在手机上运行的作业截止提醒应用。
 *
 * 设计原则（重要）：
 *  1. 不连接学习通/超星服务器，不索取也不保存学习通密码。
 *     作业数据来自「本机手动录入」或「学校官方授权的接口」。
 *  2. 所有数据只存在本机（localStorage / Android 本地存储），
 *     不上传任何服务器，不需要注册账号。
 *  3. 提醒时间在本地计算：网页版用前台定时器 + 通知 API；
 *     安卓版额外通过原生桥接注册系统闹钟，App 关闭也能提醒。
 * =========================================================== */
(function () {
  'use strict';

  /* ---------------------------------------------------------
   * 0. 常量
   * --------------------------------------------------------- */

  var APP_VERSION = '1.0.0';
  var STORE_KEY = 'hw_sentinel_v1';
  var CHECK_INTERVAL_MS = 30 * 1000;
  var MINUTE = 60 * 1000;
  var HOUR = 60 * MINUTE;
  var DAY = 24 * HOUR;

  /** 可选的「提前提醒」档位 */
  var OFFSET_OPTIONS = [
    { id: 'd3', ms: 3 * DAY, label: '3 天前', desc: '给自己留出写作业的时间' },
    { id: 'd1', ms: 1 * DAY, label: '1 天前', desc: '最常用的提醒点' },
    { id: 'h6', ms: 6 * HOUR, label: '6 小时前', desc: '该动手了' },
    { id: 'h2', ms: 2 * HOUR, label: '2 小时前', desc: '最后冲刺' },
    { id: 'm30', ms: 30 * MINUTE, label: '30 分钟前', desc: '救命提醒' }
  ];

  var DEFAULT_SETTINGS = {
    offsets: ['d1', 'h2'],       // 默认选中两个提醒档位
    dailyDigest: false,          // 每日早报
    digestTime: '07:30',
    quietHours: false,           // 夜间免打扰
    quietFrom: '23:00',
    quietTo: '07:00'
  };

  /* ---------------------------------------------------------
   * 1. 小工具
   * --------------------------------------------------------- */

  var $ = function (sel) { return document.querySelector(sel); };
  var $$ = function (sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); };

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  /** 把时间戳转成 datetime-local 输入框需要的本地时间字符串 */
  function toLocalInput(ts) {
    var d = new Date(ts);
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) +
           'T' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }

  function fromLocalInput(str) {
    if (!str) return NaN;
    var t = new Date(str).getTime();
    return isNaN(t) ? NaN : t;
  }

  function formatDateTime(ts) {
    var d = new Date(ts);
    var now = new Date();
    var sameYear = d.getFullYear() === now.getFullYear();
    return (sameYear ? '' : d.getFullYear() + '/') + (d.getMonth() + 1) + '月' + d.getDate() + '日 ' +
           pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }

  /** 把毫秒差转成「还剩 3 天 4 小时」这种中文描述 */
  function humanLeft(ms) {
    var abs = Math.abs(ms);
    var d = Math.floor(abs / DAY);
    var h = Math.floor((abs % DAY) / HOUR);
    var m = Math.floor((abs % HOUR) / MINUTE);
    if (d > 0) return d + ' 天' + (h > 0 ? ' ' + h + ' 小时' : '');
    if (h > 0) return h + ' 小时' + (m > 0 ? ' ' + m + ' 分' : '');
    if (m > 0) return m + ' 分钟';
    return '不到 1 分钟';
  }

  /** 返回「还剩 xxx」或「已超时 xxx」 */
  function leftText(ms) {
    return ms >= 0 ? '还剩 ' + humanLeft(ms) : '已超时 ' + humanLeft(ms);
  }

  /**
   * 紧急程度：
   *  overdue 已逾期 / urgent 48 小时内 / soon 7 天内 / safe 还早 / done 已完成
   */
  function urgencyOf(hw, now) {
    if (hw.done) return 'done';
    var left = hw.due - now;
    if (left < 0) return 'overdue';
    if (left <= 2 * DAY) return 'urgent';
    if (left <= 7 * DAY) return 'soon';
    return 'safe';
  }

  var URGENCY_TEXT = {
    overdue: '已截止', urgent: '紧急', soon: '接近', safe: '充裕', done: '已完成'
  };

  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /** 简易哈希：只是避免密码明文躺在本地存储里，不当作加密用 */
  function hashPass(user, pass) {
    var s = user + '::' + pass + '::hw-sentinel';
    var h1 = 0x811c9dc5, h2 = 0x1000193;
    for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      h1 = (h1 ^ c) >>> 0; h1 = (h1 * 0x01000193) >>> 0;
      h2 = (h2 + c * (i + 7)) >>> 0; h2 = ((h2 << 5) | (h2 >>> 27)) >>> 0;
    }
    return h1.toString(16) + '-' + h2.toString(16);
  }

  function uid() {
    return 'hw_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 8);
  }

  /* ---------------------------------------------------------
   * 2. 存储层
   * --------------------------------------------------------- */

  var Store = {
    read: function () {
      try {
        var raw = localStorage.getItem(STORE_KEY);
        if (!raw) return null;
        var d = JSON.parse(raw);
        return (d && typeof d === 'object') ? d : null;
      } catch (e) { return null; }
    },
    write: function (data) {
      try { localStorage.setItem(STORE_KEY, JSON.stringify(data)); return true; }
      catch (e) { return false; }
    },
    clear: function () {
      try { localStorage.removeItem(STORE_KEY); } catch (e) {}
    }
  };

  /* ---------------------------------------------------------
   * 3. 全局状态
   * --------------------------------------------------------- */

  var state = {
    user: null,              // { name, hash, createdAt }
    homework: [],            // 作业数组
    settings: Object.assign({}, DEFAULT_SETTINGS),
    ui: { view: 'home', courseFilter: 'ALL', signup: false },
    ticking: null
  };

  var nativeBridge = null;   // 安卓原生桥（网页版为 null）

  /** 把 state 落盘（不含 ui） */
  function persist() {
    Store.write({
      v: 1,
      user: state.user,
      homework: state.homework,
      settings: state.settings
    });
    // 通知安卓端重新注册系统闹钟
    if (nativeBridge && nativeBridge.syncReminders) {
      try { nativeBridge.syncReminders(JSON.stringify(planReminders(Date.now()))); }
      catch (e) { /* 忽略桥接异常，网页逻辑照常工作 */ }
    }
  }

  /* ---------------------------------------------------------
   * 4. 提醒计划（纯函数，网页与安卓共用同一套算法）
   * --------------------------------------------------------- */

  /** "HH:MM" -> 当天 0 点起的分钟数 */
  function hhmmToMin(s) {
    var p = String(s || '0:0').split(':');
    return (parseInt(p[0], 10) || 0) * 60 + (parseInt(p[1], 10) || 0);
  }

  /** 判断某个时刻是否落在免打扰时段内 */
  function inQuietHours(settings, ts) {
    if (!settings.quietHours) return false;
    var d = new Date(ts);
    var cur = d.getHours() * 60 + d.getMinutes();
    var from = hhmmToMin(settings.quietFrom);
    var to = hhmmToMin(settings.quietTo);
    if (from === to) return false;
    return from < to ? (cur >= from && cur < to) : (cur >= from || cur < to);
  }

  /** 免打扰时段内的提醒顺延到「免打扰结束」那一刻 */
  function shiftOutOfQuiet(settings, ts) {
    if (!inQuietHours(settings, ts)) return ts;
    var d = new Date(ts);
    var to = hhmmToMin(settings.quietTo);
    var out = new Date(d.getFullYear(), d.getMonth(), d.getDate(), Math.floor(to / 60), to % 60, 0, 0);
    if (out.getTime() <= ts) out = new Date(out.getTime() + DAY);
    return out.getTime();
  }

  /**
   * 生成全部待触发的提醒计划。
   * 返回 [{ key, hwId, title, body, at }]
   * key 用于去重：同一作业同一档位只提醒一次。
   */
  function planReminders(now, untilTs) {
    var s = state.settings;
    var plan = [];
    var horizon = untilTs || (now + 14 * DAY);

    state.homework.forEach(function (hw) {
      if (hw.done) return;

      s.offsets.forEach(function (offId) {
        var opt = OFFSET_OPTIONS.filter(function (o) { return o.id === offId; })[0];
        if (!opt) return;
        var at = hw.due - opt.ms;
        if (at <= now) return;           // 已经过了这个提醒点
        if (at > horizon) return;        // 超出计划范围
        plan.push({
          key: hw.id + '@' + offId,
          hwId: hw.id,
          title: '「' + (hw.course || '作业') + '」' + opt.label + '提醒',
          body: hw.title + '　' + leftText(hw.due - at) + '，截止 ' + formatDateTime(hw.due),
          at: shiftOutOfQuiet(s, at)
        });
      });

      // 到期时刻本身也提醒一次
      if (hw.due > now && hw.due <= horizon) {
        plan.push({
          key: hw.id + '@due',
          hwId: hw.id,
          title: '「' + (hw.course || '作业') + '」今天截止！',
          body: hw.title + '　截止时间 ' + formatDateTime(hw.due),
          at: shiftOutOfQuiet(s, hw.due)
        });
      }
    });

    if (s.dailyDigest) {
      var dm = hhmmToMin(s.digestTime);
      for (var i = 0; i < 15; i++) {
        var d = new Date(now);
        d.setDate(d.getDate() + i);
        d.setHours(Math.floor(dm / 60), dm % 60, 0, 0);
        var t = d.getTime();
        if (t <= now || t > horizon) continue;
        var soon = state.homework.filter(function (hw) {
          if (hw.done) return false;
          return hw.due > t - DAY && hw.due < t + 2 * DAY;
        });
        if (!soon.length) continue;
        plan.push({
          key: 'digest@' + new Date(t).toDateString(),
          hwId: null,
          title: '今天有 ' + soon.length + ' 份作业要交',
          body: soon.slice(0, 3).map(function (hw) { return hw.title; }).join('、') +
                (soon.length > 3 ? ' 等' : ''),
          at: t
        });
      }
    }

    plan.sort(function (a, b) { return a.at - b.at; });
    return plan;
  }

  /* ---------------------------------------------------------
   * 5. 通知发送
   * --------------------------------------------------------- */

  var Notifier = {
    supported: function () {
      return !!(nativeBridge && nativeBridge.notify) ||
             (typeof Notification !== 'undefined');
    },
    granted: function () {
      if (nativeBridge && nativeBridge.hasPermission) {
        try { return !!nativeBridge.hasPermission(); } catch (e) {}
      }
      if (typeof Notification === 'undefined') return false;
      return Notification.permission === 'granted';
    },
    /** 申请权限；返回 Promise<boolean> */
    request: function () {
      if (nativeBridge && nativeBridge.requestPermission) {
        try { return Promise.resolve(!!nativeBridge.requestPermission()); } catch (e) {}
      }
      if (typeof Notification === 'undefined') return Promise.resolve(false);
      if (Notification.permission === 'granted') return Promise.resolve(true);
      if (Notification.permission === 'denied') return Promise.resolve(false);
      try {
        return Notification.requestPermission().then(function (p) { return p === 'granted'; });
      } catch (e) {
        // 老版本浏览器用回调形式
        return new Promise(function (resolve) {
          Notification.requestPermission(function (p) { resolve(p === 'granted'); });
        });
      }
    },
    send: function (title, body, tag) {
      // 安卓原生：交给系统通知栏
      if (nativeBridge && nativeBridge.notify) {
        try { nativeBridge.notify(title, body, tag || ''); return true; } catch (e) {}
      }
      if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return false;
      try {
        var n = new Notification(title, { body: body, tag: tag || undefined, badge: 'icons/icon-192.png' });
        n.onclick = function () { try { window.focus(); n.close(); } catch (e) {} };
        return true;
      } catch (e) { return false; }
    }
  };

  /** 已触发过的提醒 key，避免重复打扰 */
  var firedKeys = {};

  function tickReminders() {
    var now = Date.now();
    var plan = planReminders(now);
    plan.forEach(function (item) {
      if (item.at <= now && !firedKeys[item.key]) {
        firedKeys[item.key] = true;
        Notifier.send(item.title, item.body, item.key);
      }
    });
    // 防止 firedKeys 无限增长
    var keys = Object.keys(firedKeys);
    if (keys.length > 500) firedKeys = {};
  }

  /* ---------------------------------------------------------
   * 6. 渲染
   * --------------------------------------------------------- */

  function greetingText() {
    var h = new Date().getHours();
    if (h < 6) return '夜深了';
    if (h < 11) return '早上好';
    if (h < 14) return '中午好';
    if (h < 18) return '下午好';
    return '晚上好';
  }

  function hwCardHtml(hw, now) {
    var u = urgencyOf(hw, now);
    var left = hw.due - now;
    var metaText = hw.done ? '已于 ' + formatDateTime(hw.due) + ' 截止' : leftText(left) + '　·　' + formatDateTime(hw.due);
    return '' +
      '<article class="hw hw--' + u + '" data-id="' + hw.id + '">' +
        '<span class="hw__accent"></span>' +
        '<button class="hw__check" data-act="toggle" data-id="' + hw.id + '" aria-label="标记完成">' +
          '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>' +
        '</button>' +
        '<div class="hw__body">' +
          '<div class="hw__top">' +
            '<span class="chip">' + escapeHtml(hw.course || '未分类') + '</span>' +
            '<span class="chip" style="background:var(--' +
              (u === 'overdue' || u === 'urgent' ? 'danger' : u === 'soon' ? 'warn' : u === 'done' ? 'line' : 'safe') +
              '-soft);color:var(--' +
              (u === 'overdue' || u === 'urgent' ? 'danger' : u === 'soon' ? 'warn' : u === 'done' ? 'ink-3' : 'safe') +
              ')">' + URGENCY_TEXT[u] + '</span>' +
          '</div>' +
          '<h3 class="hw__title">' + escapeHtml(hw.title) + '</h3>' +
          '<p class="hw__meta">' +
            '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 7.5V12l3 1.8"/></svg>' +
            '<span class="hw__left">' + escapeHtml(metaText) + '</span>' +
          '</p>' +
          (hw.note ? '<p class="hw__note">' + escapeHtml(hw.note) + '</p>' : '') +
        '</div>' +
      '</article>';
  }

  function emptyHtml(title, sub) {
    return '<div class="empty"><b>' + escapeHtml(title) + '</b>' + escapeHtml(sub || '') + '</div>';
  }

  function sortedHomework() {
    return state.homework.slice().sort(function (a, b) {
      if (!!a.done !== !!b.done) return a.done ? 1 : -1;
      return a.due - b.due;
    });
  }

  function renderTop() {
    var now = Date.now();
    var pending = state.homework.filter(function (h) { return !h.done; });
    var urgent = pending.filter(function (h) { return h.due - now <= 2 * DAY; }).length;
    var week = pending.filter(function (h) { return h.due - now > 2 * DAY && h.due - now <= 7 * DAY; }).length;
    var done = state.homework.filter(function (h) { return h.done; }).length;

    $('#greeting').textContent = greetingText() + '，' + (state.user ? state.user.name : '同学');
    $('#stat-urgent').textContent = urgent;
    $('#stat-week').textContent = week;
    $('#stat-done').textContent = done;
    var av = (state.user && state.user.name ? state.user.name : '同').slice(-1);
    $('#avatar').textContent = av;
    $('#me-avatar').textContent = av;
    if (state.user) {
      $('#me-name').textContent = state.user.name;
      $('#me-sub').textContent = '本机账号 · 数据只存在这台手机上';
    }
  }

  function renderHero() {
    var now = Date.now();
    var hero = $('#hero-countdown');
    var pending = state.homework.filter(function (h) { return !h.done; })
                              .sort(function (a, b) { return a.due - b.due; });

    hero.className = 'hero';

    if (!pending.length) {
      var allDone = state.homework.length > 0;
      hero.classList.add(allDone ? 'is-done' : 'is-empty');
      $('#hero-name').textContent = allDone ? '作业都写完啦 🎉' : '还没有作业记录';
      $('#hero-timer').textContent = allDone ? '全部完成' : '—';
      $('#hero-progress').style.width = '100%';
      $('#hero-meta').textContent = allDone
        ? '共完成 ' + state.homework.length + ' 份作业'
        : '点右下角的 + 手动添加一份作业';
      return;
    }

    var next = pending[0];
    var left = next.due - now;
    var u = urgencyOf(next, now);
    if (u === 'overdue' || u === 'urgent') hero.classList.add('is-urgent');

    $('#hero-name').textContent = next.title;
    $('#hero-meta').textContent = (next.course ? next.course + ' · ' : '') + '截止 ' + formatDateTime(next.due);

    var timerEl = $('#hero-timer');
    if (left < 0) {
      timerEl.innerHTML = '已超时 <small>' + escapeHtml(humanLeft(left)) + '</small>';
      $('#hero-progress').style.width = '100%';
    } else {
      timerEl.innerHTML = escapeHtml(humanLeft(left)) + '<small>后截止</small>';
      // 进度条：以 7 天为满格
      var pct = Math.max(4, Math.min(100, 100 - (left / (7 * DAY)) * 100));
      $('#hero-progress').style.width = pct.toFixed(1) + '%';
    }
  }

  function renderHome() {
    var now = Date.now();
    var sorted = sortedHomework();
    var pending = sorted.filter(function (h) { return !h.done; });
    var done = sorted.filter(function (h) { return h.done; });

    $('#pending-count').textContent = pending.length + ' 项';
    $('#list-pending').innerHTML = pending.length
      ? pending.map(function (h) { return hwCardHtml(h, now); }).join('')
      : emptyHtml('没有待完成的作业', state.homework.length ? '休息一下' : '先添加一份作业吧');

    $('#list-done').innerHTML = done.length
      ? done.map(function (h) { return hwCardHtml(h, now); }).join('')
      : emptyHtml('还没有完成的记录');
    $('#btn-clear-done').hidden = !done.length;
  }

  function renderAll() {
    var now = Date.now();
    var courses = [];
    state.homework.forEach(function (h) {
      var c = h.course || '未分类';
      if (courses.indexOf(c) < 0) courses.push(c);
    });
    courses.sort();

    if (state.ui.courseFilter !== 'ALL' && courses.indexOf(state.ui.courseFilter) < 0) {
      state.ui.courseFilter = 'ALL';
    }

    var chipHtml = '<button class="chipbtn' + (state.ui.courseFilter === 'ALL' ? ' is-active' : '') +
                   '" data-course="ALL">全部 ' + state.homework.length + '</button>';
    chipHtml += courses.map(function (c) {
      var n = state.homework.filter(function (h) { return (h.course || '未分类') === c; }).length;
      return '<button class="chipbtn' + (state.ui.courseFilter === c ? ' is-active' : '') +
             '" data-course="' + escapeHtml(c) + '">' + escapeHtml(c) + ' ' + n + '</button>';
    }).join('');
    $('#course-chips').innerHTML = chipHtml;

    var list = sortedHomework().filter(function (h) {
      return state.ui.courseFilter === 'ALL' || (h.course || '未分类') === state.ui.courseFilter;
    });

    $('#list-all').innerHTML = list.length
      ? list.map(function (h) { return hwCardHtml(h, now); }).join('')
      : emptyHtml('这里还没有作业', '点 + 添加，或在「我的」里载入示例作业');
  }

  function renderRemind() {
    var s = state.settings;
    var granted = Notifier.granted();

    $('#perm-box').className = 'perm' + (granted ? ' is-ok' : '');
    $('#perm-title').textContent = granted ? '通知已开启' : '通知未开启';
    $('#perm-desc').textContent = granted
      ? '到期前会通过手机通知提醒你'
      : '开启后，作业到期前才会提醒你';
    $('#btn-perm').textContent = granted ? '已开启' : '去授权';
    $('#btn-perm').disabled = granted;

    var foot = $('#perm-foot');
    if (foot) {
      foot.textContent = nativeBridge
        ? '提醒由手机系统闹钟触发，App 关掉也会准时提醒。所有数据只在本机。'
        : '网页版提醒需要保持本页在后台运行。安装安卓版后，App 关掉也能准时提醒。';
    }
    if (!Notifier.supported() && foot) {
      foot.textContent = '当前环境不支持通知。请安装安卓版，或把本页添加到手机主屏幕。';
    }

    $('#offset-list').innerHTML = OFFSET_OPTIONS.map(function (o) {
      var on = s.offsets.indexOf(o.id) >= 0;
      return '<label class="offset' + (on ? ' is-on' : '') + '">' +
               '<span class="offset__text"><b>' + o.label + '</b><i>' + o.desc + '</i></span>' +
               '<input type="checkbox" class="switch" data-offset="' + o.id + '"' + (on ? ' checked' : '') + ' />' +
             '</label>';
    }).join('');

    $('#switch-digest').checked = !!s.dailyDigest;
    $('#input-digest-time').value = s.digestTime;
    $('#row-digest-time').hidden = !s.dailyDigest;
    $('#switch-quiet').checked = !!s.quietHours;
    $('#quiet-range').hidden = !s.quietHours;
    $('#input-quiet-from').value = s.quietFrom;
    $('#input-quiet-to').value = s.quietTo;
  }

  function renderMe() {
    $('#about-version').textContent = APP_VERSION;
    var srcTitle = $('#source-title'), srcDesc = $('#source-desc');
    if (nativeBridge && nativeBridge.sourceName) {
      try {
        srcTitle.textContent = nativeBridge.sourceName() || '本机手动录入';
        srcDesc.textContent = nativeBridge.sourceDesc() || '作业由你自己添加。';
      } catch (e) {}
    } else {
      srcTitle.textContent = '本机手动录入';
      srcDesc.textContent = '作业由你自己添加，数据只存在本机。是否接入学习通请看 README。';
    }
  }

  function renderAll_() {
    renderTop(); renderHero(); renderHome(); renderAll(); renderRemind(); renderMe();
  }

  function setView(name) {
    state.ui.view = name;
    $$('.view').forEach(function (v) { v.hidden = v.dataset.view !== name; });
    $$('.tab').forEach(function (t) { t.classList.toggle('is-active', t.dataset.tab === name); });
    var sc = $('#content');
    if (sc) window.scrollTo({ top: 0, behavior: 'smooth' });
    if (name === 'all') renderAll();
    if (name === 'remind') renderRemind();
    if (name === 'me') renderMe();
  }

  /* ---------------------------------------------------------
   * 7. 登录
   * --------------------------------------------------------- */

  function setLoginMode(signup) {
    state.ui.signup = signup;
    $('#field-pass2').hidden = !signup;
    $('#btn-login').textContent = signup ? '创建本机账号' : '进入作业哨兵';
    $('#login-hint').textContent = signup
      ? '首次使用：设置一个只在本机生效的密码，用来防止别人翻你的作业。'
      : '本机账号：输入你设置的密码即可进入。忘记密码可在下方清除数据后重设。';
    $('#form-login').querySelector('.card__title').textContent = signup ? '创建本机账号' : '登录';
  }

  function showLoginError(msg) {
    var el = $('#login-error');
    el.textContent = msg;
    el.hidden = false;
    el.classList.remove('form-error--shake');
    void el.offsetWidth;
    el.classList.add('form-error--shake');
  }

  function handleLogin(e) {
    e.preventDefault();
    var name = $('#input-user').value.trim();
    var pass = $('#input-pass').value;
    var pass2 = $('#input-pass2').value;

    if (!name) return showLoginError('请填写学号或手机号');
    if (pass.length < 6) return showLoginError('密码至少 6 位');

    if (!state.user || state.ui.signup) {
      // 首次使用 / 主动创建
      if (state.user && state.ui.signup) {
        // 已经有账号但想重建，走「清除数据」
        return showLoginError('本机已有账号。如需重设密码，请先清除本机数据。');
      }
      if (pass !== pass2) return showLoginError('两次输入的密码不一致');
      state.user = { name: name, hash: hashPass(name, pass), createdAt: Date.now() };
      persist();
      enterApp();
      toast('账号已创建，欢迎使用');
      return;
    }

    if (hashPass(name, pass) !== state.user.hash) {
      return showLoginError('密码不对，再试一次');
    }
    enterApp();
  }

  function enterApp() {
    $('#screen-auth').hidden = true;
    $('#screen-app').hidden = false;
    state.ui.view = 'home';
    setView('home');
    renderAll_();
    startTicking();
  }

  function doLogout() {
    stopTicking();
    $('#screen-app').hidden = true;
    $('#screen-auth').hidden = false;
    $('#input-pass').value = '';
    $('#form-login').reset();
    setLoginMode(false);
  }

  /* ---------------------------------------------------------
   * 8. 作业增删改
   * --------------------------------------------------------- */

  var editingId = null;

  function openSheet(hw) {
    editingId = hw ? hw.id : null;
    $('#sheet-title').textContent = hw ? '编辑作业' : '添加作业';
    $('#btn-hw-delete').hidden = !hw;
    $('#hw-title').value = hw ? hw.title : '';
    $('#hw-course').value = hw ? (hw.course || '') : '';
    $('#hw-due').value = hw ? toLocalInput(hw.due) : toLocalInput(Date.now() + DAY);
    $('#hw-note').value = hw ? (hw.note || '') : '';
    $('#hw-error').hidden = true;

    // 课程快捷选项
    var courses = [];
    state.homework.forEach(function (h) {
      if (h.course && courses.indexOf(h.course) < 0) courses.push(h.course);
    });
    $('#course-options').innerHTML = courses.map(function (c) {
      return '<option value="' + escapeHtml(c) + '"></option>';
    }).join('');

    $('#sheet').hidden = false;
    document.body.style.overflow = 'hidden';
    setTimeout(function () { $('#hw-title').focus(); }, 120);
  }

  function closeSheet() {
    $('#sheet').hidden = true;
    document.body.style.overflow = '';
    editingId = null;
  }

  function handleSave(e) {
    e.preventDefault();
    var title = $('#hw-title').value.trim();
    var course = $('#hw-course').value.trim();
    var due = fromLocalInput($('#hw-due').value);
    var note = $('#hw-note').value.trim();
    var errEl = $('#hw-error');

    function fail(msg) {
      errEl.textContent = msg; errEl.hidden = false;
      errEl.classList.remove('form-error--shake'); void errEl.offsetWidth;
      errEl.classList.add('form-error--shake');
    }

    if (!title) return fail('作业标题不能为空');
    if (isNaN(due)) return fail('请选择截止时间');

    if (editingId) {
      state.homework = state.homework.map(function (h) {
        if (h.id !== editingId) return h;
        return Object.assign({}, h, { title: title, course: course, due: due, note: note });
      });
      toast('已保存修改');
    } else {
      state.homework.push({
        id: uid(), title: title, course: course, due: due, note: note,
        done: false, createdAt: Date.now()
      });
      toast('已添加，到期前会提醒你');
    }
    persist();
    closeSheet();
    renderAll_();
  }

  function handleDelete() {
    if (!editingId) return;
    var hw = state.homework.filter(function (h) { return h.id === editingId; })[0];
    if (!hw) return;
    if (!confirm('删除「' + hw.title + '」？')) return;
    state.homework = state.homework.filter(function (h) { return h.id !== editingId; });
    persist();
    closeSheet();
    renderAll_();
    toast('已删除');
  }

  function toggleDone(id) {
    var item = null;
    state.homework = state.homework.map(function (h) {
      if (h.id !== id) return h;
      item = Object.assign({}, h, { done: !h.done, doneAt: !h.done ? Date.now() : null });
      return item;
    });
    persist();
    renderAll_();
    if (item) toast(item.done ? '完成啦，收工 🎉' : '已恢复为待完成');
  }

  /* ---------------------------------------------------------
   * 9. 提示条
   * --------------------------------------------------------- */

  var toastTimer = null;
  function toast(msg) {
    var el = $('#toast');
    el.textContent = msg;
    el.hidden = false;
    el.style.animation = 'none'; void el.offsetWidth; el.style.animation = '';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 2000);
  }

  /* ---------------------------------------------------------
   * 10. 定时器
   * --------------------------------------------------------- */

  function startTicking() {
    stopTicking();
    tickReminders();
    state.ticking = setInterval(function () {
      tickReminders();
      // 首页的倒计时每分钟刷新一次就够
      if (state.ui.view === 'home') { renderHero(); }
      if (state.ui.view === 'home' && new Date().getSeconds() < 1) renderHome();
    }, CHECK_INTERVAL_MS);
  }

  function stopTicking() {
    if (state.ticking) { clearInterval(state.ticking); state.ticking = null; }
  }

  /* ---------------------------------------------------------
   * 11. 示例数据
   * --------------------------------------------------------- */

  function demoHomework() {
    var now = Date.now();
    return [
      { id: uid(), title: '第五章 定积分 课后习题 1-20 题', course: '高等数学', due: now + 5 * HOUR, note: '写在作业本上，拍照上传', done: false, createdAt: now },
      { id: uid(), title: '实验报告：霍尔效应测量', course: '大学物理', due: now + 30 * HOUR, note: '需要包含误差分析和数据处理', done: false, createdAt: now },
      { id: uid(), title: '读书笔记《乡土中国》第 3 章', course: '社会学概论', due: now + 3 * DAY + 4 * HOUR, note: '不少于 800 字', done: false, createdAt: now },
      { id: uid(), title: 'Unit 5 听力练习 + 作文', course: '大学英语', due: now + 6 * DAY, note: '', done: false, createdAt: now },
      { id: uid(), title: '课程设计选题申请表', course: '数据结构', due: now - 6 * HOUR, note: '已错过截止时间，尽快联系老师补交', done: false, createdAt: now },
      { id: uid(), title: '第一章 绪论 思考题', course: '马克思主义基本原理', due: now - 2 * DAY, note: '', done: true, createdAt: now, doneAt: now - DAY }
    ];
  }

  function loadDemo() {
    if (state.homework.length && !confirm('示例作业会追加到现有列表，继续吗？')) return;
    state.homework = state.homework.concat(demoHomework());
    persist();
    renderAll_();
    toast('已载入示例作业');
  }

  /* ---------------------------------------------------------
   * 12. 事件绑定
   * --------------------------------------------------------- */

  function bindEvents() {

    // ---- 登录 ----
    $('#form-login').addEventListener('submit', handleLogin);

    $('#btn-toggle-pass').addEventListener('click', function () {
      var inp = $('#input-pass');
      var on = inp.type === 'password';
      inp.type = on ? 'text' : 'password';
      this.classList.toggle('is-on', on);
    });

    // 回车切换到确认密码
    $('#input-pass').addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !$('#field-pass2').hidden) {
        e.preventDefault(); $('#input-pass2').focus();
      }
    });

    // 双击标题可以清除本机数据（忘记密码时的出路）
    $('.brand__name').addEventListener('dblclick', function () {
      if (confirm('清除本机全部数据（账号、作业、设置）？此操作不可恢复。')) {
        Store.clear();
        location.reload();
      }
    });

    // ---- 底部导航 ----
    $('#tabbar').addEventListener('click', function (e) {
      var tab = e.target.closest('.tab');
      if (tab) setView(tab.dataset.tab);
    });

    // ---- 顶部快捷筛选 ----
    $$('.stat').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var kind = btn.dataset.jump;
        if (kind === 'done') { setView('home'); window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' }); return; }
        setView('all');
        state.ui.courseFilter = 'ALL';
        renderAll();
        // 滚到第一份符合条件的作业
        var now = Date.now();
        var target = sortedHomework().filter(function (h) {
          if (h.done) return false;
          var left = h.due - now;
          return kind === 'urgent' ? left <= 2 * DAY : (left > 2 * DAY && left <= 7 * DAY);
        })[0];
        if (target) {
          var el = document.querySelector('.hw[data-id="' + target.id + '"]');
          if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
      });
    });

    // ---- 刷新 ----
    $('#btn-refresh').addEventListener('click', function () {
      var btn = this;
      btn.classList.add('is-spin');
      setTimeout(function () {
        btn.classList.remove('is-spin');
        renderAll_();
        toast('已刷新');
      }, 620);
    });

    // ---- 作业卡片：勾选 / 编辑 ----
    document.addEventListener('click', function (e) {
      var act = e.target.closest('[data-act="toggle"]');
      if (act) { e.stopPropagation(); toggleDone(act.dataset.id); return; }

      var card = e.target.closest('.hw');
      if (card) {
        var hw = state.homework.filter(function (h) { return h.id === card.dataset.id; })[0];
        if (hw) openSheet(hw);
        return;
      }

      var chip = e.target.closest('.chipbtn');
      if (chip) { state.ui.courseFilter = chip.dataset.course; renderAll(); }
    });

    // ---- 新增 ----
    $('#btn-add').addEventListener('click', function () { openSheet(null); });

    // ---- 弹层 ----
    $('#sheet-scrim').addEventListener('click', closeSheet);
    $('#btn-hw-cancel').addEventListener('click', closeSheet);
    $('#btn-hw-delete').addEventListener('click', handleDelete);
    $('#form-hw').addEventListener('submit', handleSave);
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !$('#sheet').hidden) closeSheet();
    });

    // ---- 提醒设置 ----
    $('#offset-list').addEventListener('change', function (e) {
      var cb = e.target.closest('[data-offset]');
      if (!cb) return;
      var id = cb.dataset.offset;
      var idx = state.settings.offsets.indexOf(id);
      if (cb.checked && idx < 0) state.settings.offsets.push(id);
      if (!cb.checked && idx >= 0) state.settings.offsets.splice(idx, 1);
      // 保持固定顺序，便于阅读
      state.settings.offsets = OFFSET_OPTIONS
        .map(function (o) { return o.id; })
        .filter(function (o) { return state.settings.offsets.indexOf(o) >= 0; });
      cb.closest('.offset').classList.toggle('is-on', cb.checked);
      persist(); renderRemind();
      toast(cb.checked ? '已开启 ' + cb.closest('.offset').querySelector('b').textContent + ' 提醒' : '已关闭该提醒');
    });

    $('#switch-digest').addEventListener('change', function () {
      state.settings.dailyDigest = this.checked;
      $('#row-digest-time').hidden = !this.checked;
      persist(); renderRemind();
    });

    $('#input-digest-time').addEventListener('change', function () {
      state.settings.digestTime = this.value || '07:30';
      persist();
    });

    $('#switch-quiet').addEventListener('change', function () {
      state.settings.quietHours = this.checked;
      $('#quiet-range').hidden = !this.checked;
      persist(); renderRemind();
    });

    $('#input-quiet-from').addEventListener('change', function () {
      state.settings.quietFrom = this.value || '23:00'; persist();
    });
    $('#input-quiet-to').addEventListener('change', function () {
      state.settings.quietTo = this.value || '07:00'; persist();
    });

    // ---- 通知权限 ----
    $('#btn-perm').addEventListener('click', function () {
      var btn = this;
      btn.disabled = true;
      Notifier.request().then(function (ok) {
        btn.disabled = false;
        renderRemind();
        if (ok) { toast('通知已开启'); Notifier.send('作业哨兵已就绪', '之后作业到期前会在这里提醒你', 'hello'); }
        else if (nativeBridge) { toast('请在系统设置里允许本应用发送通知'); }
        else { toast('浏览器拒绝了通知权限，可在地址栏左侧的设置里打开'); }
      });
    });

    $('#btn-test-notify').addEventListener('click', function () {
      if (!Notifier.granted()) { toast('请先开启通知权限'); return; }
      var ok = Notifier.send('测试通知 ✅', '看到这条说明提醒已经能正常工作', 'test');
      toast(ok ? '已发送，看看通知栏' : '发送失败，请检查通知权限');
    });

    // ---- 我的 ----
    $('#btn-load-demo').addEventListener('click', loadDemo);

    $('#btn-logout').addEventListener('click', function () {
      if (confirm('退出登录？作业数据会保留在本机，下次登录仍可看到。')) doLogout();
    });

    $('#btn-wipe').addEventListener('click', function () {
      if (!confirm('清除本机全部数据？账号、作业、提醒设置都会被删除，且无法恢复。')) return;
      if (!confirm('再确认一次：真的要全部清除吗？')) return;
      Store.clear();
      location.reload();
    });

    // 回到前台时立刻检查一次（手机锁屏后再打开很常见）
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && state.user) { tickReminders(); renderAll_(); }
    });
  }

  /* ---------------------------------------------------------
   * 13. 启动
   * --------------------------------------------------------- */

  function ready(fn) {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
    else fn();
  }

  /** 读取 URL 参数（仅用于本机自测与截图，见 app/selftest.html） */
  function query(name) {
    var m = new RegExp('[?&]' + name + '=([^&#]*)').exec(location.search);
    return m ? decodeURIComponent(m[1]) : null;
  }

  ready(function () {
    // 安卓原生桥：安卓版会注入 window.HWSentinel
    if (window.HWSentinel) nativeBridge = window.HWSentinel;

    var saved = Store.read();
    if (saved) {
      state.user = saved.user || null;
      state.homework = Array.isArray(saved.homework) ? saved.homework : [];
      state.settings = Object.assign({}, DEFAULT_SETTINGS, saved.settings || {});
    }

    // 兜底：修正脏数据
    state.homework = state.homework.filter(function (h) {
      return h && h.id && h.title && typeof h.due === 'number' && !isNaN(h.due);
    });

    bindEvents();
    $('#about-version').textContent = APP_VERSION;

    if (state.user) {
      enterApp();
    } else {
      setLoginMode(true);
      $('#screen-auth').hidden = false;
    }

    // 本机自测：?view=home|all|remind|me 可直接打开指定页签
    var onlyLocal = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
    var wantView = query('view');
    if (onlyLocal && wantView && state.user && ['home', 'all', 'remind', 'me'].indexOf(wantView) >= 0) {
      setView(wantView);
    }

    // 注册 Service Worker（网页版离线可用；安卓 WebView 里会自动跳过）
    if ('serviceWorker' in navigator && location.protocol.indexOf('http') === 0) {
      navigator.serviceWorker.register('sw.js').catch(function () {});
    }

    // 暴露少量接口，方便原生层或调试调用
    window.HWSentinelWeb = {
      version: APP_VERSION,
      reload: function () { renderAll_(); startTicking(); },
      plan: function () { return planReminders(Date.now()); },
      statistics: function () {
        return {
          total: state.homework.length,
          pending: state.homework.filter(function (h) { return !h.done; }).length
        };
      }
    };
  });

})();
