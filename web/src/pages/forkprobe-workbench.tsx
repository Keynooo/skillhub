import { PipelineWorkbench } from '@/features/forkprobe/pipeline-workbench'

/**
 * forkprobe — one task, forked into parallel skill lanes (browser-style tabs),
 * run side by side. Skill-detail pages still link here with
 * ?preselect=namespace/slug; the param is accepted for compatibility but the
 * entry experience is identical to arriving from the nav: the task input first,
 * and the preselected skill is highlighted once recommendations load.
 */
export function ForkprobeWorkbenchPage() {
  return <PipelineWorkbench />
}
