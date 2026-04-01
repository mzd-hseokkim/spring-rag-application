/**
 * 전역 API 로딩 스피너.
 * /api/ 경로로 향하는 모든 fetch 호출에 자동으로 스피너 오버레이를 표시한다.
 */

let activeRequests = 0;
let overlayEl: HTMLDivElement | null = null;
const originalFetch = window.fetch.bind(window);

function getOrCreateOverlay(): HTMLDivElement {
  if (overlayEl) return overlayEl;

  overlayEl = document.createElement('div');
  overlayEl.id = 'global-loading-overlay';
  overlayEl.innerHTML = `
    <div class="global-spinner"></div>
  `;
  overlayEl.style.cssText = `
    position: fixed;
    inset: 0;
    z-index: 99999;
    display: none;
    align-items: center;
    justify-content: center;
    background: rgba(0, 0, 0, 0.08);
    pointer-events: none;
  `;

  const style = document.createElement('style');
  style.textContent = `
    .global-spinner {
      width: 32px;
      height: 32px;
      border: 3px solid hsl(var(--muted-foreground, 220 9% 46%) / 0.3);
      border-top-color: hsl(var(--primary, 220 90% 56%));
      border-radius: 50%;
      animation: global-spin 0.6s linear infinite;
    }
    @keyframes global-spin {
      to { transform: rotate(360deg); }
    }
  `;

  document.head.appendChild(style);
  document.body.appendChild(overlayEl);
  return overlayEl;
}

function startLoading(): void {
  activeRequests++;
  if (activeRequests === 1) {
    getOrCreateOverlay().style.display = 'flex';
  }
}

function stopLoading(): void {
  activeRequests = Math.max(0, activeRequests - 1);
  if (activeRequests === 0 && overlayEl) {
    overlayEl.style.display = 'none';
  }
}

function isApiUrl(input: RequestInfo | URL): boolean {
  const url = typeof input === 'string' ? input : input instanceof URL ? input.pathname : (input as Request).url;
  return url.includes('/api/');
}

/**
 * 전역 fetch를 패치하여 /api/ 호출 시 스피너를 표시한다.
 * main.tsx 등 앱 초기화 시 한 번 호출한다.
 */
export function installLoadingSpinner(): void {
  window.fetch = async function (input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
    if (!isApiUrl(input)) {
      return originalFetch(input, init);
    }
    startLoading();
    try {
      return await originalFetch(input, init);
    } finally {
      stopLoading();
    }
  };
}
