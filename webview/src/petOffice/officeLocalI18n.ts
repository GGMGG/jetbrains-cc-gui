import { createInstance } from 'i18next';
import { initReactI18next } from 'react-i18next';

const en = {
  exitOffice: 'Exit Office',
  windowSettings: 'Window settings', officeName: 'Office name', topmost: 'Keep window on top', zoom: 'Zoom', save: 'Save', saving: 'Saving…',
  reloadSaved: 'Reload saved values', nameTooLong: 'Use at most 64 characters.', preferencesRecovered: 'Settings were recovered from the validated backup.',
  preferencesFailed: 'Settings could not be confirmed. Your draft is kept. Reload saved values before retrying.',
  title: 'Pet Office', hint: 'Live sessions across your IDEs. Select a desk to return to its chat tab. Closing hides this window; Exit Office restores floating pets.', refresh: 'Refresh',
  project: 'Project', allProjects: 'All projects', sessionCount: '{{count}} sessions', updated: 'Updated {{time}}',
  readFailed: 'Session status is unavailable. Refresh to reconnect.', loading: 'Reading session status…',
  capacity: 'The session limit was reached. Some sessions may be missing.', empty: 'No matching chat tabs.',
  room: 'Session desks', activate: 'Open chat: {{title}}', floors: 'Office floors', floor: 'Floor {{current}} of {{total}}', previous: 'Previous', next: 'Next',
  phases: { IDLE: 'Idle', QUEUED: 'Queued', THINKING: 'Thinking', RUNNING: 'Working', WAITING_USER: 'Waiting for your answer', WAITING_PERMISSION: 'Waiting for permission', WAITING_PLAN: 'Waiting for plan review',
    STOPPING: 'Stopping', SUCCEEDED: 'Completed', FAILED: 'Failed', CANCELLED: 'Cancelled', UNKNOWN: 'Status unknown' },
  navigation: { ACTIVATED: 'Chat tab activated.', ATTENTION_REQUESTED: 'The chat window requested attention.', TARGET_GONE: 'The original chat tab is no longer available.',
    STALE_INTERACTION: 'This interaction has changed. Refresh and try again.', UNAVAILABLE: 'The chat tab could not be activated.' },
};
const zh: typeof en = {
  exitOffice: '退出办公室',
  windowSettings: '窗口设置', officeName: '办公室名称', topmost: '窗口置顶', zoom: '缩放', save: '保存', saving: '正在保存…',
  reloadSaved: '重新载入已保存值', nameTooLong: '名称最多 64 个字符。', preferencesRecovered: '已从经过校验的备份恢复设置。',
  preferencesFailed: '暂时无法确认保存结果，草稿已保留。请重新载入已保存值后重试。',
  title: '宠物办公室', hint: '查看各 IDE 的真实会话状态，点击工位返回聊天。关闭窗口会隐藏办公室；退出办公室后恢复悬浮宠物。', refresh: '刷新',
  project: '项目', allProjects: '全部项目', sessionCount: '{{count}} 个会话', updated: '更新于 {{time}}',
  readFailed: '暂时无法读取会话状态，请刷新重连。', loading: '正在读取会话状态…',
  capacity: '会话数量已达上限，部分会话可能未显示。', empty: '没有符合条件的聊天标签页。',
  room: '会话工位', activate: '打开聊天：{{title}}', floors: '办公室楼层', floor: '第 {{current}} / {{total}} 层', previous: '上一层', next: '下一层',
  phases: { IDLE: '空闲', QUEUED: '排队中', THINKING: '思考中', RUNNING: '工作中', WAITING_USER: '等待回答', WAITING_PERMISSION: '等待授权', WAITING_PLAN: '等待审批计划', STOPPING: '正在停止',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消', UNKNOWN: '状态未知' },
  navigation: { ACTIVATED: '已返回聊天标签页。', ATTENTION_REQUESTED: '聊天窗口已请求前台显示。', TARGET_GONE: '原聊天标签页已不可用。',
    STALE_INTERACTION: '交互状态已变化，请刷新后重试。', UNAVAILABLE: '暂时无法切换到聊天标签页。' },
};

const i18n = createInstance();
void i18n.use(initReactI18next).init({ resources: { en: { translation: en }, zh: { translation: zh } },
  lng: window.__PET_OFFICE_LANGUAGE__ || 'en', fallbackLng: 'en', interpolation: { escapeValue: false } });
export default i18n;
