const CACHE = 'mers-remote-v21';
const ASSETS = ['.', 'index.html', 'manifest.webmanifest', 'mers-logo.png', 'mers-gen-map.js'];
const DYNAMIC_PATHS = ['/mers-proxy/', '/mers-ping'];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(ASSETS)));
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key !== CACHE).map(key => caches.delete(key)))));
  self.clients.claim();
});

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;
  const url = new URL(event.request.url);
  if (url.origin === location.origin && DYNAMIC_PATHS.some(path => url.pathname.startsWith(path))) {
    event.respondWith(fetch(event.request));
    return;
  }
  event.respondWith(
    fetch(event.request)
      .then(response => {
        const cacheCopy = response.clone();
        caches.open(CACHE).then(cache => cache.put(event.request, cacheCopy));
        return response;
      })
      .catch(() => caches.match(event.request))
  );
});
