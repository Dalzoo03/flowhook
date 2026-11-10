import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { Type } from '@earendil-works/pi-ai';
import { defineTool, type ExtensionAPI } from '@earendil-works/pi-coding-agent';
import { RunStore, loadTask, listTasks, verify, status, conciseReport, safeRelative, isAllowed } from '../harness/core.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));

export default function (pi: ExtensionAPI) {
  let checking: Promise<any> | undefined;
  let checkController: AbortController | undefined;
  const storeFor = (ctx: any) => new RunStore(root, ctx.sessionManager.getSessionId());
  const show = (content: string) => pi.sendMessage({ customType: 'flowhook', content, display: true }, { triggerTurn: false });
  const check = (ctx: any, signal?: AbortSignal) => {
    if (checking) return checking;
    checkController = new AbortController();
    const combined = signal ? AbortSignal.any([signal, checkController.signal]) : checkController.signal;
    checking = verify(storeFor(ctx), { signal: combined }).finally(() => { checking = undefined; checkController = undefined; });
    return checking;
  };

  pi.registerCommand('fh-task', {
    description: 'FlowHook 작업 시작: /fh-task 001',
    handler: async (args, ctx) => {
      if (!ctx.isIdle() || checking) { show('현재 작업이 끝난 뒤 시작해주세요.'); return; }
      if (!args.trim()) { show((await listTasks(root)).join('\n')); return; }
      try {
        const task = await loadTask(root, args.trim());
        const brief = await readFile(new URL(`../../${task.brief}`, import.meta.url), 'utf8');
        await storeFor(ctx).start(task);
        pi.sendUserMessage(`다음 FlowHook 작업을 구현하세요. 허용 경로: ${task.allowedPaths.join(', ')}. 구현 후 flowhook_verify로 검증하세요. 검증은 최대 3회이며 기준을 완화하지 마세요. 마지막에 변경·실제 검증 결과·학습 과제를 한국어로 설명하세요.\n\n${brief}`);
      } catch (error) { show(String(error)); }
    },
  });
  pi.registerCommand('fh-check', {
    description: '현재 작업의 빌드·테스트·HTTP 확인',
    handler: async (_args, ctx) => {
      if (!ctx.isIdle()) { show('현재 작업이 끝난 뒤 실행해주세요.'); return; }
      try { show(conciseReport(await check(ctx))); } catch (error) { show(String(error)); }
    },
  });
  pi.registerCommand('fh-status', {
    description: '현재 작업과 마지막 검증 상태',
    handler: async (_args, ctx) => show(JSON.stringify(await status(storeFor(ctx)), null, 2)),
  });
  pi.registerCommand('fh-stop', {
    description: '현재 작업과 자동 수정 중단',
    handler: async (_args, ctx) => {
      ctx.abort();
      checkController?.abort();
      if (checking) await checking.catch(() => {});
      const store = storeFor(ctx);
      const state = await store.read();
      if (state) { state.active = false; await store.save(state); }
      show('FlowHook 자동 작업을 중단했습니다. 작성된 파일은 유지됩니다.');
    },
  });
  pi.registerTool(defineTool({
    name: 'flowhook_verify', label: 'FlowHook 검증',
    description: '선택한 FlowHook 작업의 실제 빌드, 테스트, HTTP와 변경 범위를 검증합니다. 최대 3회.',
    parameters: Type.Object({}),
    async execute(_id, _params, signal, _onUpdate, ctx) {
      const report = await check(ctx, signal);
      return { content: [{ type: 'text', text: conciseReport(report) }], details: report };
    },
  }));
  pi.on('tool_call', async (event, ctx) => {
    if (event.toolName !== 'write' && event.toolName !== 'edit') return;
    const state = await storeFor(ctx).read();
    if (!state?.active) return;
    const file = event.input.path;
    if (typeof file !== 'string') return;
    const relative = safeRelative(root, path.resolve(ctx.cwd, file));
    if (!isAllowed(relative, state.task.allowedPaths)) return { block: true, reason: `현재 작업 범위 밖입니다: ${relative}` };
  });
  pi.on('agent_before_settle', async (event, ctx) => {
    if (event.outcome !== 'completed' || ctx.hasPendingMessages()) return;
    const store = storeFor(ctx);
    let state = await store.read();
    if (!state?.active) return;
    let current = await status(store);
    if (current.status !== 'passed' && state.verificationRuns < state.task.maxVerificationRuns) {
      // A failed explicit check already contains feedback; avoid spending a second check on unchanged files.
      if (current.status !== 'failed') await check(ctx, ctx.signal);
      state = await store.read();
      current = await status(store);
    }
    const entry = (content: string) => ({ type: 'custom_message' as const, customType: 'flowhook', content, display: true });
    if (current.status === 'passed') {
      state.active = false; await store.save(state);
      return { entries: [entry(`검증 통과. 보고서: ${state.last.reportPath}. 코드 리뷰와 학습 확인은 별도로 진행해주세요.`)] };
    }
    if (event.context.canContinue && state.verificationRuns < state.task.maxVerificationRuns && state.repairContinuations < 2) {
      state.repairContinuations++; await store.save(state);
      return { entries: [entry(`검증 실패 또는 코드 변경으로 재검증이 필요합니다. 원인을 수정하고 flowhook_verify를 실행하세요. 기준을 완화하지 마세요.\n${state.last ? conciseReport(state.last) : '검증 결과 없음'}`)], continue: true };
    }
    state.active = false; await store.save(state);
    return { entries: [entry('자동 작업을 종료했습니다. 검증 통과가 확인되지 않았습니다. /fh-status로 결과를 확인하세요.')] };
  });
}
