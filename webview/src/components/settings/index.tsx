import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { CodexProviderConfig } from '../../types/provider';
import { ToastContainer } from '../Toast';

// Import split-out components
import SettingsHeader from './SettingsHeader';
import SettingsSidebar, { type SettingsTab } from './SettingsSidebar';
import BasicConfigSection from './BasicConfigSection';
import ProviderTabSection, { type ProviderManageTab } from './ProviderTabSection';
import DependencySection from './DependencySection';
import UsageSection from './UsageSection';
import PlaceholderSection from './PlaceholderSection';
import PermissionsSection from './PermissionsSection';
import CommunitySection from './CommunitySection';
import AgentSection from './AgentSection';
import PromptSection from './PromptSection';
import CommitSection from './CommitSection';
import PromptEnhancerSection from './PromptEnhancerSection';
import OtherSettingsSection from './OtherSettingsSection';
import PetSettingsSection from './PetSettingsSection';
import AiDataStorageSection from './AiDataStorageSection';
import { SkillsSettingsSection } from '../skills';
import SettingsDialogs from './SettingsDialogs';
import { setNewSessionConfirmEnabled as persistNewSessionConfirmEnabled } from '../../utils/skipNewSessionConfirm';

// Import custom hooks
import {
  useProviderManagement,
  useCodexProviderManagement,
  useAgentManagement,
  useSettingsWindowCallbacks,
  useSettingsPageState,
  useSettingsThemeSync,
  useSettingsBasicActions,
} from './hooks';
import { useLazyTabData } from './hooks/useLazyTabData';
import { useSaveProviderFromDialog } from './hooks/useSaveProviderFromDialog';

import styles from './style.module.less';

interface SettingsViewProps {
  onClose: () => void;
  initialTab?: SettingsTab;
  /** Deep link into the Providers tab's sub-tab (claude/codex/cli) */
  initialProviderSubTab?: ProviderManageTab;
  currentProvider: 'claude' | 'codex' | string;
  // Streaming configuration (passed from App.tsx for state sync)
  streamingEnabled?: boolean;
  onStreamingEnabledChange?: (enabled: boolean) => void;
  // Send shortcut configuration (passed from App.tsx for state sync)
  sendShortcut?: 'enter' | 'cmdEnter';
  onSendShortcutChange?: (shortcut: 'enter' | 'cmdEnter') => void;
  // Auto open file configuration (passed from App.tsx for state sync)
  autoOpenFileEnabled?: boolean;
  onAutoOpenFileEnabledChange?: (enabled: boolean) => void;
  // Permission dialog timeout configuration (passed from App.tsx for state sync)
  permissionDialogTimeoutSeconds?: number;
  onPermissionDialogTimeoutChange?: (seconds: number) => void;
}

const SettingsView = ({
  onClose,
  initialTab,
  initialProviderSubTab,
  currentProvider,
  streamingEnabled: streamingEnabledProp,
  onStreamingEnabledChange: onStreamingEnabledChangeProp,
  sendShortcut: sendShortcutProp,
  onSendShortcutChange: onSendShortcutChangeProp,
  autoOpenFileEnabled: autoOpenFileEnabledProp,
  onAutoOpenFileEnabledChange: onAutoOpenFileEnabledChangeProp,
  permissionDialogTimeoutSeconds: permissionDialogTimeoutSecondsProp,
  onPermissionDialogTimeoutChange: onPermissionDialogTimeoutChangeProp,
}: SettingsViewProps) => {
  const { t } = useTranslation();
  const isCodexMode = currentProvider === 'codex';
  // Codex mode: align with Claude capabilities for settings tabs.
  // Keep the Codex pet settings available.
  const disabledTabs = useMemo<SettingsTab[]>(
    () => [],
    []
  );

  // Page state: tabs, toasts, sidebar collapse, alert dialog
  const pageState = useSettingsPageState({ initialTab, isCodexMode, disabledTabs });

  // Theme sync: theme preference, IDE theme, font size, chat colors
  const themeSync = useSettingsThemeSync();

  // Basic settings actions: node path, working dir, streaming, shortcuts, sound, commit prompt, etc.
  const basicActions = useSettingsBasicActions({
    streamingEnabledProp,
    onStreamingEnabledChangeProp,
    sendShortcutProp,
    onSendShortcutChangeProp,
    autoOpenFileEnabledProp,
    onAutoOpenFileEnabledChangeProp,
    permissionDialogTimeoutSecondsProp,
    onPermissionDialogTimeoutChangeProp,
    currentProvider,
  });

  // Use provider management hook
  const providerManagement = useProviderManagement({
    onError: (msg) => pageState.showAlert('error', t('common.error'), msg),
    onSuccess: (msg) => pageState.addToast(msg, 'success'),
  });

  // Use Codex provider management hook
  const codexProviderManagement = useCodexProviderManagement({
    onSuccess: (msg) => pageState.addToast(msg, 'success'),
  });

  // Use agent management hook
  const agentManagement = useAgentManagement({
    onSuccess: (msg) => pageState.addToast(msg, 'success'),
  });

  // Note: Prompt management is now handled internally by PromptSection component

  useLazyTabData(pageState.currentTab, {
    loadProviders: providerManagement.loadProviders,
    loadCodexProviders: codexProviderManagement.loadCodexProviders,
    loadAgents: agentManagement.loadAgents,
  });

  // Register window callbacks for Java bridge communication
  useSettingsWindowCallbacks({
    ...themeSync,
    ...basicActions,
    ...pageState,
    ...providerManagement,
    ...codexProviderManagement,
    ...agentManagement,
    onStreamingEnabledChangeProp,
    onSendShortcutChangeProp,
  });

  // Save provider (wrapper function with validation logic)
  const handleSaveProviderFromDialog = useSaveProviderFromDialog({
    providerDialog: providerManagement.providerDialog,
    providers: providerManagement.providers,
    syncActiveProviderModelMapping: providerManagement.syncActiveProviderModelMapping,
    handleCloseProviderDialog: providerManagement.handleCloseProviderDialog,
    setLoading: providerManagement.setLoading,
    showAlert: pageState.showAlert,
    addToast: pageState.addToast,
  });

  // Save Codex provider (wrapper function with validation logic)
  const handleSaveCodexProviderFromDialog = (providerData: CodexProviderConfig) => {
    codexProviderManagement.handleSaveCodexProvider(providerData);
  };

  // Save agent (wrapper function with validation logic)
  const handleSaveAgentFromDialog = (data: { name: string; prompt: string }) => {
    agentManagement.handleSaveAgent(data);
  };

  return (
    <div className={styles.settingsPage}>
      {/* Top header bar */}
      <SettingsHeader onClose={onClose} />

      {/* Main content */}
      <div className={styles.settingsMain}>
        {/* Sidebar */}
        <SettingsSidebar
          currentTab={pageState.currentTab}
          onTabChange={pageState.handleTabChange}
          isCollapsed={pageState.isCollapsed}
          onToggleCollapse={pageState.toggleManualCollapse}
          disabledTabs={disabledTabs}
          onDisabledTabClick={(tab) =>
            pageState.addToast(
              t(tab === 'pet' ? 'settings.pet.temporarilyUnavailable' : 'settings.codexFeatureUnavailable'),
              'warning'
            )
          }
        />

        {/* Content area — mount only the active tab.
            Previously every tab stayed mounted under display:none, which made
            Settings open cost ~all sections (MCP/Skills/TokenTracker/…) at once. */}
        <div className={`${styles.settingsContent} ${currentTab === 'providers' ? styles.providerSettingsContent : ''}`}>
          {currentTab === 'basic' && (
            <BasicConfigSection
              theme={themePreference}
              onThemeChange={setThemePreference}
              fontSizeLevel={fontSizeLevel}
              onFontSizeLevelChange={setFontSizeLevel}
              nodePath={nodePath}
              onNodePathChange={setNodePath}
              onSaveNodePath={handleSaveNodePath}
              savingNodePath={savingNodePath}
              nodeVersion={nodeVersion}
              minNodeVersion={minNodeVersion}
              claudeCliPath={claudeCliPath}
              onClaudeCliPathChange={setClaudeCliPath}
              onSaveClaudeCliPath={handleSaveClaudeCliPath}
              savingClaudeCliPath={savingClaudeCliPath}
              workingDirectory={workingDirectory}
              onWorkingDirectoryChange={setWorkingDirectory}
              onSaveWorkingDirectory={handleSaveWorkingDirectory}
              savingWorkingDirectory={savingWorkingDirectory}
              editorFontConfig={editorFontConfig}
              uiFontConfig={uiFontConfig}
              codeFontConfig={codeFontConfig}
              onUiFontSelectionChange={handleUiFontSelectionChange}
              onSaveUiFontCustomPath={handleSaveUiFontCustomPath}
              onBrowseUiFontFile={handleBrowseUiFontFile}
              onCodeFontSelectionChange={handleCodeFontSelectionChange}
              onSaveCodeFontCustomPath={handleSaveCodeFontCustomPath}
              onBrowseCodeFontFile={handleBrowseCodeFontFile}
              streamingEnabled={streamingEnabled}
              onStreamingEnabledChange={handleStreamingEnabledChange}
              sendShortcut={sendShortcut}
              onSendShortcutChange={handleSendShortcutChange}
              autoOpenFileEnabled={autoOpenFileEnabled}
              onAutoOpenFileEnabledChange={handleAutoOpenFileEnabledChange}
              chatBgColor={chatBgColor}
              onChatBgColorChange={setChatBgColor}
              userMsgColor={userMsgColor}
              onUserMsgColorChange={setUserMsgColor}
              chatBarColor={chatBarColor}
              onChatBarColorChange={setChatBarColor}
              diffTheme={diffTheme}
              onDiffThemeChange={setDiffTheme}
              diffExpandedByDefault={diffExpandedByDefault}
              onDiffExpandedByDefaultChange={setDiffExpandedByDefault}
              commitGenerationEnabled={commitGenerationEnabled}
              onCommitGenerationEnabledChange={(enabled) => {
                handleCommitGenerationEnabledChange(enabled);
                addToast(t('toast.restartRequired'), 'warning');
              }}
              statusBarWidgetEnabled={statusBarWidgetEnabled}
              onStatusBarWidgetEnabledChange={(enabled) => {
                handleStatusBarWidgetEnabledChange(enabled);
                addToast(t('toast.restartRequired'), 'warning');
              }}
              aiTitleGenerationEnabled={aiTitleGenerationEnabled}
              onAiTitleGenerationEnabledChange={handleAiTitleGenerationEnabledChange}
              newSessionConfirmEnabled={!skipNewSessionConfirm}
              onNewSessionConfirmEnabledChange={(enabled) => {
                // Optimistic local update so the toggle reflects instantly even if
                // the CustomEvent loops back. persistNewSessionConfirmEnabled writes
                // to localStorage and dispatches the sync event for other surfaces.
                setSkipNewSessionConfirm(!enabled);
                persistNewSessionConfirmEnabled(enabled);
              }}
              soundNotificationEnabled={soundNotificationEnabled}
              onSoundNotificationEnabledChange={handleSoundNotificationEnabledChange}
              soundOnlyWhenUnfocused={soundOnlyWhenUnfocused}
              onSoundOnlyWhenUnfocusedChange={handleSoundOnlyWhenUnfocusedChange}
              selectedSound={selectedSound}
              onSelectedSoundChange={handleSelectedSoundChange}
              customSoundPath={customSoundPath}
              onCustomSoundPathChange={handleCustomSoundPathChange}
              onSaveCustomSoundPath={handleSaveCustomSoundPath}
              onTestSound={handleTestSound}
              onBrowseSound={handleBrowseSound}
              taskCompletionNotificationEnabled={taskCompletionNotificationEnabled}
              onTaskCompletionNotificationEnabledChange={handleTaskCompletionNotificationEnabledChange}
              askUserQuestionNotificationEnabled={askUserQuestionNotificationEnabled}
              onAskUserQuestionNotificationEnabledChange={handleAskUserQuestionNotificationEnabledChange}
              detailedOutputEnabled={detailedOutputEnabled}
              onDetailedOutputEnabledChange={handleDetailedOutputEnabledChange}
              systemNotificationOnlyWhenUnfocused={systemNotificationOnlyWhenUnfocused}
              onSystemNotificationOnlyWhenUnfocusedChange={handleSystemNotificationOnlyWhenUnfocusedChange}
              askUserQuestionSoundNotificationEnabled={askUserQuestionSoundNotificationEnabled}
              onAskUserQuestionSoundNotificationEnabledChange={handleAskUserQuestionSoundNotificationEnabledChange}
              permissionDialogTimeoutSeconds={permissionDialogTimeoutSeconds}
              onPermissionDialogTimeoutChange={handlePermissionDialogTimeoutChange}
            />
          )}

          {currentTab === 'providers' && (
            <ProviderTabSection
              currentProvider={currentProvider}
              initialSubTab={initialProviderSubTab}
              providers={providers}
              loading={loading}
              onAddProvider={handleAddProvider}
              onEditProvider={handleEditProvider}
              onDeleteProvider={handleDeleteProvider}
              onSwitchProvider={handleSwitchProvider}
              codexProviders={codexProviders}
              codexLoading={codexLoading}
              onAddCodexProvider={handleAddCodexProvider}
              onEditCodexProvider={handleEditCodexProvider}
              onDeleteCodexProvider={handleDeleteCodexProvider}
              onSwitchCodexProvider={handleSwitchCodexProvider}
              onRevokeCodexLocalConfigAuthorization={handleRevokeCodexLocalConfigAuthorization}
              addToast={addToast}
            />
          )}

          {currentTab === 'dependencies' && (
            <DependencySection addToast={addToast} isActive />
          )}

          {currentTab === 'usage' && <UsageSection />}

          {currentTab === 'mcp' && (
            <PlaceholderSection type="mcp" currentProvider={currentProvider} />
          )}

          {currentTab === 'permissions' && (
            currentProvider === 'codex' ? (
              <PermissionsSection
                codexSandboxMode={codexSandboxMode}
                onCodexSandboxModeChange={handleCodexSandboxModeChange}
              />
            ) : (
              <PlaceholderSection type="permissions" />
            )
          )}

          {currentTab === 'promptEnhancer' && (
            <PromptEnhancerSection
              promptEnhancerConfig={promptEnhancerConfig}
              onPromptEnhancerProviderChange={handlePromptEnhancerProviderChange}
              onPromptEnhancerModelChange={handlePromptEnhancerModelChange}
              onPromptEnhancerResetToDefault={handlePromptEnhancerResetToDefault}
            />
          )}

          {currentTab === 'commit' && (
            <CommitSection
              commitAiConfig={commitAiConfig}
              onCommitAiProviderChange={handleCommitAiProviderChange}
              onCommitAiModelChange={handleCommitAiModelChange}
              onCommitAiResetToDefault={handleCommitAiResetToDefault}
              commitPrompt={commitPrompt}
              projectCommitPrompt={projectCommitPrompt}
              onCommitPromptChange={setCommitPrompt}
              onProjectCommitPromptChange={setProjectCommitPrompt}
              onSaveCommitPrompt={handleSaveCommitPrompt}
              onSaveProjectCommitPrompt={handleSaveProjectCommitPrompt}
              savingCommitPrompt={savingCommitPrompt}
              savingProjectCommitPrompt={savingProjectCommitPrompt}
            />
          )}

          {currentTab === 'agents' && (
            <AgentSection
              agents={agents}
              loading={agentsLoading}
              onAdd={handleAddAgent}
              onEdit={handleEditAgent}
              onDelete={handleDeleteAgent}
              onExport={handleExportAgents}
              onImport={handleImportAgentsFile}
            />
          )}

          {currentTab === 'prompts' && (
            <PromptSection
              currentProvider={currentProvider}
              onSuccess={(msg) => addToast(msg, 'success')}
            />
          )}

          {currentTab === 'skills' && (
            <SkillsSettingsSection currentProvider={currentProvider} />
          )}

          {currentTab === 'pet' && <PetSettingsSection addToast={addToast} />}

          {currentTab === 'storage' && <AiDataStorageSection addToast={addToast} />}

          {currentTab === 'other' && (
            <OtherSettingsSection
              historyCompletionEnabled={historyCompletionEnabled}
              onHistoryCompletionEnabledChange={(enabled) => {
                setHistoryCompletionEnabled(enabled);
                localStorage.setItem('historyCompletionEnabled', enabled.toString());
                // Dispatch custom event for same-tab sync (localStorage 'storage' event only fires for cross-tab)
                window.dispatchEvent(new CustomEvent('historyCompletionChanged', { detail: { enabled } }));
              }}
            />
          )}

          {currentTab === 'community' && (
            <CommunitySection addToast={addToast} />
          )}
        </div>
      </div>

      <SettingsDialogsHost
        pageState={pageState}
        providerManagement={providerManagement}
        codexProviderManagement={codexProviderManagement}
        agentManagement={agentManagement}
        onSaveProvider={handleSaveProviderFromDialog}
        onSaveCodexProvider={handleSaveCodexProviderFromDialog}
        onSaveAgent={handleSaveAgentFromDialog}
      />

      {/* Toast notifications */}
      <ToastContainer messages={pageState.toasts} onDismiss={pageState.dismissToast} />
    </div>
  );
};

export default SettingsView;
