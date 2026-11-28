(() => {
  "use strict";

  // Only translate as the reader approaches a post. Do not traverse the whole
  // timeline for AI requests: X virtualizes its feed and constantly mutates it.
  const TWEET_SELECTOR = '[data-testid="tweetText"]';
  const MAX_CHARS = 12000;
  const MAX_PENDING = 8;
  const MAX_CACHED = 160;
  const ABOVE_PX = 450;
  const BELOW_PX = 900;
  const CACHE_TTL_MS = 30 * 60 * 1000;
  const observed = new WeakSet();
  const displayedKeys = new WeakMap();
  const pendingKeys = new WeakMap();
  const skipped = new Map(); // Bounded, per-tab negative detection cache.
  const results = new Map(); // Memory-only per tab. Never persist tweet text to Chrome storage.
  let tasks = [];
  let active = null;
  let running = false;
  let autoTranslate = false;
  let targetLang = "en";
  let serial = 0;
  let lastScroll = window.scrollY;
  let scrollDirection = "down";
  let scrollTimer = null;
  let observerReady = false;

  function tweetLanguage(node) {
    // Look ONLY at the tweet, never the page <html lang="en"> ancestor:
    // otherwise a Japanese/Spanish post could be mistaken for English.
    const explicit = node.getAttribute("lang") ||
      node.querySelector("[lang]")?.getAttribute("lang") || "";
    const match = explicit.toLowerCase().match(/^([a-z]{2,3})(?:[-_]|$)/);
    return match ? match[1] : null;
  }

  function snapshot(node) {
    const text = node.innerText?.trim() || "";
    if (text.length < 3 || text.length > MAX_CHARS) return null;
    const sourceLang = tweetLanguage(node);
    return {
      text, sourceLang,
      key: JSON.stringify([text, sourceLang || "auto", targetLang])
    };
  }

  function readCache(key) {
    const record = results.get(key);
    if (!record) return null;
    if (Date.now() - record.at > CACHE_TTL_MS) {
      results.delete(key);
      return null;
    }
    results.delete(key);
    results.set(key, record);
    return record.translation;
  }

  function writeCache(key, translation) {
    results.delete(key);
    results.set(key, { translation, at:Date.now() });
    while (results.size > MAX_CACHED) {
      results.delete(results.keys().next().value);
    }
  }

  function viewportDistance(node) {
    const box = node.getBoundingClientRect();
    if (box.top >= window.innerHeight) return box.top - window.innerHeight;
    if (box.bottom <= 0) return -box.bottom;
    return 0;
  }

  function isNearby(node) {
    if (!node.isConnected) return false;
    const box = node.getBoundingClientRect();
    return box.bottom > -ABOVE_PX && box.top < window.innerHeight + BELOW_PX;
  }

  function taskPriority(task) {
    if (task.manual) return -1000000 + task.sequence;
    const box = task.node.getBoundingClientRect();
    const behind = scrollDirection === "down"
      ? box.bottom < 0 : box.top > window.innerHeight;
    return viewportDistance(task.node) + (behind ? 1000 : 0) +
      task.sequence * 0.00001;
  }

  function getActions(node) {
    return node.parentElement?.querySelector(":scope > .roots-local-actions") || null;
  }

  function addButton(node) {
    if (!node.parentElement || getActions(node)) return;
    const actions = document.createElement("div");
    actions.className = "roots-local-actions";
    actions.style.cssText = "margin:6px 0;color:inherit;font:12px system-ui,sans-serif";
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "Translate locally · PLaMo";
    button.style.cssText = "cursor:pointer;border:1px solid #6b7280;border-radius:12px;padding:3px 9px;background:transparent;color:inherit";
    button.addEventListener("click", () => queueTranslation(node, true));
    actions.appendChild(button);
    node.parentElement.appendChild(actions);
  }

  function show(node, translation, error = false) {
    const actions = getActions(node);
    if (!actions || !node.isConnected) return;
    displayedKeys.set(node, snapshot(node)?.key);
    let output = actions.querySelector(".roots-local-result");
    if (!output) {
      output = document.createElement("div");
      output.className = "roots-local-result";
      output.style.cssText = "white-space:pre-wrap;padding:7px 2px;line-height:1.45";
      actions.appendChild(output);
    }
    output.textContent = error
      ? translation
      : translation + "\nTranslated locally · Built with PLaMo";
    output.style.opacity = error ? "0.75" : "1";
  }

  function clearStaleResult(node) {
    if (displayedKeys.has(node) && displayedKeys.get(node) !== snapshot(node)?.key) {
      getActions(node)?.querySelector('.roots-local-result')?.remove();
      displayedKeys.delete(node);
    }
  }

  function renderIfVisible(node) {
    clearStaleResult(node);
    if (!node.isConnected || viewportDistance(node) > 0) return;
    const item = snapshot(node);
    if (!item) return;
    const cached = readCache(item.key);
    if (cached) show(node, cached);
  }

  function queueTranslation(node, manual = false) {
    clearStaleResult(node);
    const item = snapshot(node);
    if (!item) return;
    if (!manual && !autoTranslate) return;
    if (!manual && Date.now() - (skipped.get(item.key) || 0) < CACHE_TTL_MS) return;
    if (!manual && !isNearby(node)) return;

    const cached = readCache(item.key);
    if (cached) {
      if (manual) show(node, cached);
      else renderIfVisible(node);
      return;
    }
    if (pendingKeys.get(node) === item.key) return;
    pendingKeys.set(node, item.key);

    tasks.push({
      node, ...item, manual, sequence:++serial, requestId:"tr-" + serial
    });
    if (tasks.length > MAX_PENDING) {
      // Discard distant, automatically queued jobs before sending them to GPU.
      tasks.sort((a, b) => taskPriority(a) - taskPriority(b));
      const dropped = tasks.splice(MAX_PENDING);
      for (const task of dropped) {
        if (pendingKeys.get(task.node) === task.key) pendingKeys.delete(task.node);
      }
    }
    schedulePump(manual ? 0 : 120);
  }

  let pumpTimer = null;
  function schedulePump(delay) {
    if (pumpTimer !== null) {
      if (delay !== 0) return;
      clearTimeout(pumpTimer);
    }
    pumpTimer = setTimeout(() => { pumpTimer = null; pump(); }, delay);
  }

  function pump() {
    if (active || !tasks.length) return;
    tasks.sort((a, b) => taskPriority(a) - taskPriority(b));
    while (tasks.length) {
      const item = tasks.shift();
      if (pendingKeys.get(item.node) !== item.key) continue;
      if (!item.node.isConnected || (!item.manual && !isNearby(item.node)) ||
          snapshot(item.node)?.key !== item.key) {
        pendingKeys.delete(item.node);
        continue;
      }
      active = item;
      const treePost = globalThis.RootsTreeCapture?.snapshot(item.node);
      const requestedTarget = targetLang;
      chrome.runtime.sendMessage({
        type:"sixStarsTranslate",
        requestId:item.requestId,
        text:item.text,
        sourceLang:item.sourceLang,
        auto:!item.manual
      }, response => {
        const messageError = chrome.runtime.lastError?.message;
        if (!messageError && treePost && response?.ok)
          globalThis.RootsTreeCapture?.translated(treePost,response,requestedTarget);
        if (active === item) active = null;
        if (pendingKeys.get(item.node) === item.key) pendingKeys.delete(item.node);
        if (!messageError && response?.ok && response.skipped) {
          skipped.set(item.key, Date.now());
          while (skipped.size > MAX_CACHED) skipped.delete(skipped.keys().next().value);
        } else if (!messageError && response?.ok && typeof response.translation === "string") {
          writeCache(item.key, response.translation);
          if (snapshot(item.node)?.key === item.key) {
            if (item.manual) show(item.node, response.translation);
            else renderIfVisible(item.node);
          }
        } else if (item.manual && item.node.isConnected && snapshot(item.node)?.key === item.key) {
          show(item.node, response?.error || messageError || "Translation unavailable", true);
        }
        schedulePump(0);
      });
      return;
    }
  }

  const nearObserver = new IntersectionObserver(entries => {
    if (!autoTranslate) return;
    for (const entry of entries) {
      if (entry.isIntersecting) queueTranslation(entry.target);
    }
  }, { root:null, rootMargin:ABOVE_PX + "px 0px " + BELOW_PX + "px 0px", threshold:0 });

  // Cache translations ahead of time; do NOT insert translated lines below a
  // far-away tweet. Inserting them only when visible avoids feed scroll jumps.
  const visibleObserver = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        renderIfVisible(entry.target);
        if (autoTranslate) queueTranslation(entry.target);
      }
    }
  }, {root:null, threshold:0});

  function register(node) {
    if (observed.has(node)) return;
    observed.add(node);
    addButton(node);
    nearObserver.observe(node);
    visibleObserver.observe(node);
  }

  function discover(root) {
    if (!(root instanceof Element)) return;
    if (root.matches(TWEET_SELECTOR)) register(root);
    root.querySelectorAll(TWEET_SELECTOR).forEach(register);
  }

  // Single initial pass; later discoveries inspect only newly inserted subtrees.
  discover(document.documentElement);
  new MutationObserver(mutations => {
    for (const m of mutations) {
      for (const child of m.addedNodes) {
        if (child.nodeType === Node.ELEMENT_NODE) discover(child);
      }
      const container = m.target instanceof Element ? m.target : m.target.parentElement;
      const editedTweet = container?.closest(TWEET_SELECTOR);
      if (editedTweet) {
        clearStaleResult(editedTweet);
        if (autoTranslate && isNearby(editedTweet)) queueTranslation(editedTweet);
      }
      for (const child of m.removedNodes) {
        if (!(child instanceof Element)) continue;
        const nodes = child.matches(TWEET_SELECTOR) ? [child, ...child.querySelectorAll(TWEET_SELECTOR)] : child.querySelectorAll(TWEET_SELECTOR);
        for (const node of nodes) {
          if (node.isConnected) continue;
          nearObserver.unobserve(node);
          visibleObserver.unobserve(node);
          observed.delete(node);
          pendingKeys.delete(node);
        }
      }
    }
  }).observe(document.documentElement, { subtree:true, childList:true, characterData:true });

  function handleScroll() {
    const y = window.scrollY;
    if (Math.abs(y - lastScroll) > 8) scrollDirection = y > lastScroll ? "down" : "up";
    lastScroll = y;
    if (scrollTimer !== null) return;
    scrollTimer = setTimeout(() => {
      scrollTimer = null;
      if (active && !active.manual && !isNearby(active.node)) {
        chrome.runtime.sendMessage({type:"sixStarsCancel",requestId:active.requestId});
      }
      if (tasks.length) schedulePump(0);
    }, 140);
  }
  window.addEventListener("scroll", handleScroll, {passive:true});

  chrome.runtime.sendMessage({type:"sixStarsSettings"}, state => {
    if (chrome.runtime.lastError) return;
    autoTranslate = state?.ok === true && state.autoTranslate !== false;
    targetLang = typeof state?.targetLang === "string" ? state.targetLang : "en";
    observerReady = true;
    // IntersectionObserver callbacks run after observe(), but if settings arrive
    // later, nodes currently near the viewport need this one-time inexpensive pass.
    if (autoTranslate) {
      document.querySelectorAll(TWEET_SELECTOR).forEach(node => {
        if (isNearby(node)) queueTranslation(node);
      });
    }
  });
})();
