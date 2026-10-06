import { describe, expect, it, vi } from "vitest";
import { bulkSelectionHint, runWithConcurrency } from "./queue-bulk";

/** A promise the test resolves/rejects by hand, so completion order is under the test's control. */
function deferred() {
  let resolve!: () => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<void>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe("runWithConcurrency", () => {
  it("never has more calls in flight than the limit", async () => {
    let inFlight = 0;
    let peak = 0;
    const ids = Array.from({ length: 23 }, (_, i) => i + 1);
    await runWithConcurrency(
      ids,
      async () => {
        inFlight += 1;
        peak = Math.max(peak, inFlight);
        await new Promise((r) => setTimeout(r, 1));
        inFlight -= 1;
      },
      { concurrency: 4 },
    );
    expect(peak).toBe(4);
    expect(inFlight).toBe(0);
  });

  it("calls each id exactly once", async () => {
    const seen: number[] = [];
    await runWithConcurrency([5, 6, 7, 8, 9, 10], async (id) => void seen.push(id), { concurrency: 4 });
    expect([...seen].sort((a, b) => a - b)).toEqual([5, 6, 7, 8, 9, 10]);
  });

  it("reports results in input order, not completion order", async () => {
    const gates = new Map([1, 2, 3].map((id) => [id, deferred()]));
    const run = runWithConcurrency([1, 2, 3], (id) => gates.get(id)!.promise, { concurrency: 3 });
    // Settle back-to-front.
    gates.get(3)!.resolve();
    await flush();
    gates.get(2)!.reject(new Error("two failed"));
    await flush();
    gates.get(1)!.resolve();
    const result = await run;
    expect(result.ok).toEqual([1, 3]);
    expect(result.failed).toEqual([{ id: 2, message: "two failed" }]);
  });

  it("captures failures per id without aborting the rest", async () => {
    const result = await runWithConcurrency(
      [1, 2, 3, 4, 5],
      async (id) => {
        if (id % 2 === 0) throw new Error(`bad ${id}`);
      },
      { concurrency: 2 },
    );
    expect(result.ok).toEqual([1, 3, 5]);
    expect(result.failed).toEqual([
      { id: 2, message: "bad 2" },
      { id: 4, message: "bad 4" },
    ]);
  });

  it("captures a synchronous throw from the call as a failure", async () => {
    const result = await runWithConcurrency([1, 2], (id) => {
      if (id === 1) throw new Error("sync");
      return Promise.resolve();
    });
    expect(result).toEqual({ ok: [2], failed: [{ id: 1, message: "sync" }] });
  });

  it("uses describeError for the failure message", async () => {
    const result = await runWithConcurrency([7], () => Promise.reject({ code: "SOD_VIOLATION" }), {
      describeError: (e) => (e as { code: string }).code,
    });
    expect(result.failed).toEqual([{ id: 7, message: "SOD_VIOLATION" }]);
  });

  it("reports progress once per settled id, ending at total", async () => {
    const onProgress = vi.fn();
    await runWithConcurrency(
      [1, 2, 3, 4, 5, 6],
      async (id) => {
        if (id === 3) throw new Error("x");
      },
      { concurrency: 4, onProgress },
    );
    expect(onProgress).toHaveBeenCalledTimes(6);
    expect(onProgress.mock.calls.map(([done]) => done)).toEqual([1, 2, 3, 4, 5, 6]);
    expect(onProgress.mock.calls.every(([, total]) => total === 6)).toBe(true);
  });

  it("runs strictly one at a time with concurrency 1", async () => {
    const order: string[] = [];
    await runWithConcurrency(
      [1, 2, 3],
      async (id) => {
        order.push(`start ${id}`);
        await flush();
        order.push(`end ${id}`);
      },
      { concurrency: 1 },
    );
    expect(order).toEqual(["start 1", "end 1", "start 2", "end 2", "start 3", "end 3"]);
  });

  it("clamps a nonsense concurrency to 1", async () => {
    let peak = 0;
    let inFlight = 0;
    await runWithConcurrency(
      [1, 2, 3],
      async () => {
        inFlight += 1;
        peak = Math.max(peak, inFlight);
        await flush();
        inFlight -= 1;
      },
      { concurrency: 0 },
    );
    expect(peak).toBe(1);
  });

  it("keeps running every id and still resolves when onProgress throws", async () => {
    const call = vi.fn(async () => {});
    const result = await runWithConcurrency([1, 2, 3, 4, 5], call, {
      concurrency: 2,
      onProgress: () => {
        throw new Error("render blew up");
      },
    });
    expect(call).toHaveBeenCalledTimes(5);
    expect(result).toEqual({ ok: [1, 2, 3, 4, 5], failed: [] });
  });

  it("records a failure with a fallback message when describeError itself throws", async () => {
    const call = vi.fn(async (id: number) => {
      if (id === 2) throw new Error("upstream 500");
    });
    const result = await runWithConcurrency([1, 2, 3], call, {
      concurrency: 1,
      describeError: () => {
        throw new Error("formatter bug");
      },
    });
    expect(call).toHaveBeenCalledTimes(3);
    expect(result).toEqual({ ok: [1, 3], failed: [{ id: 2, message: "upstream 500" }] });
  });

  it("resolves an empty run without calling anything", async () => {
    const call = vi.fn();
    const onProgress = vi.fn();
    expect(await runWithConcurrency([], call, { onProgress })).toEqual({ ok: [], failed: [] });
    expect(call).not.toHaveBeenCalled();
    expect(onProgress).not.toHaveBeenCalled();
  });
});

describe("bulkSelectionHint", () => {
  it("names both actions only when both are offered", () => {
    expect(bulkSelectionHint({ canAssign: true, canReject: true })).toBe("Tick rows to assign or reject in bulk");
  });

  it("never mentions assign on a reject-only queue", () => {
    expect(bulkSelectionHint({ canAssign: false, canReject: true })).toBe("Tick rows to reject in bulk");
  });

  it("names assign alone when reject is not offered", () => {
    expect(bulkSelectionHint({ canAssign: true, canReject: false })).toBe("Tick rows to assign in bulk");
  });

  it("is null when no bulk action is offered", () => {
    expect(bulkSelectionHint({ canAssign: false, canReject: false })).toBeNull();
  });
});
