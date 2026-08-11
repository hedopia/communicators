import { useEffect, useRef } from "react";

/** invoke callback every intervalMs while enabled is true */
export function useAutoRefresh(enabled: boolean, intervalMs: number, callback: () => void) {
  const callbackRef = useRef(callback);
  callbackRef.current = callback;
  useEffect(() => {
    if (!enabled) return;
    const id = setInterval(() => callbackRef.current(), intervalMs);
    return () => clearInterval(id);
  }, [enabled, intervalMs]);
}
