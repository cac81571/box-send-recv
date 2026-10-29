package jp.boxsendrecv;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class BoxBrowserAutomation implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BoxBrowserAutomation.class);

    private final Config config;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private boolean connectedViaCdp;
    private String activeFolderUrl;
    private boolean nestedFolderUpload;

    public BoxBrowserAutomation(Config config) {
        this.config = config;
    }

    public void startSession() throws Exception {
        activeFolderUrl = config.getBoxFolderUrl();
        Files.createDirectories(config.getLocalWatchPath());
        openBrowser();
        ensureLoggedIn();
        navigateToFolder();
        log.info("Box 送受信セッションを開始しました: " + activeFolderUrl);
    }

    public boolean uploadAndRename(Path localFile) throws IOException {
        String originalName = localFile.getFileName().toString();
        String remoteName = config.stripSendPrefix(originalName);
        List<String> folders = relativeFolderNames(localFile);
        Path uploadSource = copyForUpload(localFile, remoteName);
        nestedFolderUpload = !folders.isEmpty();
        try {
            ensureOnTargetFolder();
            for (String folder : folders) {
                enterOrCreateFolder(folder);
            }
            if (!folders.isEmpty()) {
                log.info("Box 送信先フォルダ: " + String.join("/", folders));
            }
            uploadFile(uploadSource, remoteName);
        } finally {
            nestedFolderUpload = false;
            deleteTempUpload(uploadSource);
            ensureOnTargetFolder();
        }
        renameInPlace(localFile, remoteName);
        return false;
    }

    public boolean downloadAndRename(Path localFile) throws IOException {
        String remoteName = config.stripReceivePrefix(localFile.getFileName().toString());
        Path destination = localFile.resolveSibling(remoteName);
        List<String> folders = relativeFolderNames(localFile);
        nestedFolderUpload = !folders.isEmpty();
        try {
            ensureOnTargetFolder();
            for (String folder : folders) {
                enterExistingFolder(folder);
            }
            if (!folders.isEmpty()) {
                log.info("Box 受信元フォルダ: " + String.join("/", folders));
            }
            downloadFile(remoteName, destination);
        } finally {
            nestedFolderUpload = false;
            ensureOnTargetFolder();
        }
        if (!localFile.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())) {
            Files.deleteIfExists(localFile);
            log.info("受信マークを削除: " + localFile.getFileName());
        }
        return false;
    }

    private Path copyForUpload(Path localFile, String remoteName) throws IOException {
        Path tempDir = Files.createTempDirectory("box-send-recv-");
        Path copy = tempDir.resolve(remoteName);
        Files.copy(localFile, copy, StandardCopyOption.REPLACE_EXISTING);
        return copy;
    }

    private void deleteTempUpload(Path uploadSource) {
        try {
            Files.deleteIfExists(uploadSource);
            Path parent = uploadSource.getParent();
            if (parent != null) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException e) {
            log.warn("一時ファイルの削除に失敗しました: " + uploadSource + " (" + e.getMessage() + ")");
        }
    }

    private void renameInPlace(Path sourceFile, String newName) throws IOException {
        Path destination = sourceFile.resolveSibling(newName);
        if (sourceFile.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize())) {
            return;
        }
        if (Files.exists(destination)) {
            Files.delete(destination);
        }
        Files.move(sourceFile, destination, StandardCopyOption.REPLACE_EXISTING);
        log.info("処理後リネーム: " + sourceFile.getFileName() + " -> " + newName);
    }

    private void uploadFile(Path localFile, String remoteName) {
        withTimeoutRetry("アップロード: " + remoteName, () -> {
            uploadFileOnce(localFile, remoteName);
            return null;
        });
    }

    private void uploadFileOnce(Path localFile, String remoteName) {
        String fileName = remoteName;
        log.info("アップロード開始: " + fileName + " (Box 上の名前)");
        dismissOverlays();
        if (!nestedFolderUpload) {
            guardTargetFolder("アップロード前");
            ensureOnTargetFolder();
        }

        if (!tryUploadViaFileChooser(localFile)) {
            log.info("ファイル選択ダイアログ方式失敗。hidden input 方式を試行します");
            if (!nestedFolderUpload) {
                guardTargetFolder("ファイル選択ダイアログ失敗後");
            }
            if (!triggerUploadViaHiddenInput(localFile)) {
                throw new IllegalStateException("アップロードを開始できませんでした: " + fileName);
            }
        }

        waitForUploadComplete(fileName, true);
        log.info("アップロード完了: " + fileName + " -> " + page.url());
        dismissOverlays();
        if (!nestedFolderUpload) {
            guardTargetFolder("アップロード完了後");
            ensureOnTargetFolder();
        }
    }

    private boolean triggerUploadViaHiddenInput(Path localFile) {
        dismissOverlays();
        guardTargetFolder("hidden input 方式開始前");
        boolean menuOpened = openUploadFileDialog();
        if (!menuOpened) {
            log.info("アップロードメニューを開けませんでした。file input 直接設定を試行します");
        } else {
            guardTargetFolder("アップロードメニュー選択後");
        }

        page.waitForTimeout(800);
        setFilesToUploadInput(localFile);
        page.waitForTimeout(1500);
        handleUploadConflictDialogs();
        guardTargetFolder("ファイル設定後");

        String fileName = localFile.getFileName().toString();
        if (hasUploadInProgress() || isFileListedInFolder(fileName)) {
            return true;
        }

        if (menuOpened) {
            log.info("アップロード進行表示は未検出ですが、メニュー操作と file input 設定は完了しました");
            return true;
        }

        log.info("アップロード開始の兆候が見つかりません（進行表示・ファイル行なし）");
        return false;
    }

    private void setFilesToUploadInput(Path localFile) {
        Locator fileInputs = page.locator("input[type='file']");
        int count = fileInputs.count();
        log.info("file input 数: " + count);

        if (count == 0) {
            throw new IllegalStateException("アップロード用の file input が見つかりません");
        }

        Exception lastError = null;
        for (int i = count - 1; i >= 0; i--) {
            try {
                fileInputs.nth(i).setInputFiles(localFile);
                log.info("file input [" + i + "] にファイルを設定しました");
                return;
            } catch (Exception e) {
                lastError = e;
            }
        }

        throw new IllegalStateException("file input へのファイル設定に失敗しました", lastError);
    }

    private boolean tryUploadViaFileChooser(Path localFile) {
        try {
            page.setDefaultTimeout(10000);
            FileChooser fileChooser = page.waitForFileChooser(this::openUploadFileDialog);
            fileChooser.setFiles(localFile);
            page.setDefaultTimeout(config.getBrowserTimeoutMs());
            guardTargetFolder("ファイル選択ダイアログ設定後");
            return true;
        } catch (Exception e) {
            page.setDefaultTimeout(config.getBrowserTimeoutMs());
            log.warn("ファイル選択ダイアログ待機失敗: " + e.getMessage());
            guardTargetFolder("ファイル選択ダイアログ待機失敗後");
            return false;
        }
    }

    private Locator browseToolbar() {
        return page.locator(
            "[data-resin-target='header'], [data-testid='browse-header'], "
            + "[class*='BrowseHeader'], [data-resin-target='pageHeader'], "
            + "[data-testid='browse-content-pane'] [data-resin-target='header']"
        );
    }

    private Locator browseContentArea() {
        return page.locator("[data-testid='browse-content-pane'], [data-resin-target='fileList']").first();
    }

    private boolean openUploadFileDialog() {
        log.info("アップロードUI操作前 URL: " + page.url());

        if (openBlueprintUploadMenu()) {
            return true;
        }

        if (clickUploadInArea(browseToolbar(), "ツールバー")) {
            return true;
        }

        if (clickUploadInArea(browseContentArea(), "コンテンツ領域")) {
            return true;
        }

        if (clickUploadButtonExcludingSidebar()) {
            return true;
        }

        log.info("アップロードボタンが見つかりません");
        return false;
    }

    private Locator newItemMenuButton() {
        return page.locator(
            "[data-testid='new-item-menu-button'], [data-resin-target='unifiednewmenubutton']"
        ).first();
    }

    private boolean openBlueprintUploadMenu() {
        if (!waitForNewItemMenuButtonReady()) {
            log.info("新規メニューボタンが見つからないか、活性化しませんでした");
            return false;
        }

        Locator button = newItemMenuButton();
        if (isInSidebar(button)) {
            log.info("新規メニューボタンがサイドバー内のためスキップします");
            return false;
        }

        log.info("新規メニューボタンをクリック");
        button.click();
        page.waitForTimeout(500);
        guardTargetFolder("新規メニューボタン後");

        if (clickBlueprintFileUploadMenuItem()) {
            guardTargetFolder("ファイルアップロードメニュー選択後");
            return true;
        }

        return false;
    }

    private boolean waitForNewItemMenuButtonReady() {
        Locator button = newItemMenuButton();
        if (button.count() == 0) {
            return false;
        }

        try {
            button.waitFor(new Locator.WaitForOptions().setTimeout(config.getBrowserTimeoutMs()));
        } catch (Exception e) {
            return false;
        }

        long deadline = System.currentTimeMillis() + config.getBrowserTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            if (button.isVisible() && button.isEnabled() && !isAriaDisabled(button)) {
                log.info("新規メニューボタンが活性化しました");
                return true;
            }
            page.waitForTimeout(500);
        }

        return button.isVisible() && button.isEnabled() && !isAriaDisabled(button);
    }

    private boolean isAriaDisabled(Locator element) {
        String ariaDisabled = element.getAttribute("aria-disabled");
        String disabled = element.getAttribute("disabled");
        return "true".equalsIgnoreCase(ariaDisabled) || disabled != null;
    }

    private boolean clickBlueprintFileUploadMenuItem() {
        List<String> targetIds = List.of(
            "MenuItem-uploadNewFile",
            "MenuItem-uploadFiles",
            "MenuItem-uploadFile"
        );

        for (String targetId : targetIds) {
            Locator item = page.locator("[role='menuitem'][data-target-id='" + targetId + "']").first();
            if (item.count() > 0 && item.isVisible()) {
                log.info("アップロードメニューをクリック: " + targetId);
                item.click();
                page.waitForTimeout(500);
                return true;
            }
        }

        Locator blueprintItems = page.locator("[role='menuitem'][data-testid='upload-menu-item-blueprint']");
        int count = blueprintItems.count();
        for (int i = 0; i < count; i++) {
            Locator item = blueprintItems.nth(i);
            if (!item.isVisible()) {
                continue;
            }

            String text = item.innerText();
            if (text != null && text.contains("ファイル") && !text.contains("フォルダ")) {
                log.info("アップロードメニューをクリック: " + text.trim());
                item.click();
                page.waitForTimeout(500);
                return true;
            }
        }

        return clickUploadFilesMenuItem();
    }

    private boolean clickUploadInArea(Locator area, String areaName) {
        if (area.count() == 0) {
            return false;
        }

        List<String> selectors = List.of(
            "[data-resin-target='upload']",
            "[data-testid='upload-menu-button']",
            "[data-resin-target='uploadMenu']",
            "[aria-label='Upload']",
            "[aria-label='アップロード']",
            "[aria-label*='アップロード']",
            "[aria-label*='Upload']",
            "[title*='アップロード']",
            "[title*='Upload']"
        );

        for (String selector : selectors) {
            Locator button = area.locator(selector).first();
            if (button.count() > 0 && button.isVisible()) {
                log.info(areaName + " のアップロードボタンをクリック: " + selector);
                button.click();
                page.waitForTimeout(500);
                if (clickUploadFilesMenuItem()) {
                    guardTargetFolder(areaName + " アップロードボタン後");
                    return true;
                }
            }
        }

        List<String> buttonLabels = List.of("アップロード", "Upload", "新規", "New");
        for (String label : buttonLabels) {
            Locator button = area.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(label)).first();
            if (button.count() > 0 && button.isVisible()) {
                log.info(areaName + " のボタンをクリック: " + label);
                button.click();
                page.waitForTimeout(500);
                guardTargetFolder(areaName + " " + label + " ボタン後");
                if (clickUploadFilesMenuItem()) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean clickUploadButtonExcludingSidebar() {
        Locator candidates = page.locator(
            "[data-resin-target='upload'], [data-testid='upload-menu-button'], "
            + "[aria-label='Upload'], [aria-label='アップロード'], [aria-label*='アップロード'], "
            + "[aria-label*='Upload'], button:has-text('アップロード'), button:has-text('Upload')"
        );

        int count = candidates.count();
        log.info("ページ内アップロード候補数: " + count);

        for (int i = 0; i < count; i++) {
            Locator button = candidates.nth(i);
            if (!button.isVisible() || isInSidebar(button)) {
                continue;
            }

            log.info("ページ内アップロードボタンをクリック (index=" + i + ")");
            button.click();
            page.waitForTimeout(500);
            guardTargetFolder("ページ内アップロードボタン後");
            if (clickUploadFilesMenuItem()) {
                return true;
            }
        }

        for (String label : List.of("新規", "New")) {
            Locator buttons = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(label));
            int buttonCount = buttons.count();
            for (int i = 0; i < buttonCount; i++) {
                Locator button = buttons.nth(i);
                if (!button.isVisible() || isInSidebar(button)) {
                    continue;
                }

                log.info("ページ内 " + label + " ボタンをクリック (index=" + i + ")");
                button.click();
                page.waitForTimeout(500);
                guardTargetFolder("ページ内 " + label + " ボタン後");
                if (clickUploadFilesMenuItem()) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isInSidebar(Locator element) {
        try {
            Object inSidebar = element.evaluate(
                "el => !!el.closest('[data-resin-target=\"sideNavigation\"], nav[class*=\"Sidebar\"], [class*=\"LeftNav\"]')"
            );
            return Boolean.TRUE.equals(inSidebar);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean clickUploadFilesMenuItem() {
        return clickMenuItem(List.of(
            "ファイルのアップロード", "ファイルをアップロード", "ファイルアップロード",
            "Upload Files", "Upload File", "Files", "File"
        ));
    }

    private List<String> relativeFolderNames(Path localFile) {
        Path root = config.getLocalWatchPath().toAbsolutePath().normalize();
        Path relative;
        try {
            relative = root.relativize(localFile.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        if (relative.getNameCount() <= 1) {
            return List.of();
        }
        Path parent = relative.getParent();
        if (parent == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Path part : parent) {
            String name = part.toString();
            if (!name.isBlank() && !".".equals(name) && !"..".equals(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private void enterExistingFolder(String folderName) {
        dismissOverlays();
        ensureFolderView();
        if (!openFolderFromListing(folderName)) {
            throw new IllegalStateException("Box フォルダが見つかりません: " + folderName);
        }
    }

    private void enterOrCreateFolder(String folderName) {
        dismissOverlays();
        ensureFolderView();
        if (openFolderFromListing(folderName)) {
            return;
        }
        log.info("Box にフォルダを作成します: " + folderName);
        createFolder(folderName);
        if (!openFolderFromListing(folderName)) {
            throw new IllegalStateException("Box フォルダを開けませんでした: " + folderName);
        }
    }

    private void createFolder(String folderName) {
        if (!openNewFolderDialog()) {
            throw new IllegalStateException("新規フォルダのメニューを開けませんでした: " + folderName);
        }
        if (!submitNewFolderName(folderName)) {
            throw new IllegalStateException("フォルダ名を入力できませんでした: " + folderName);
        }
        if (!waitUntilFolderListed(folderName, 20000)) {
            logFolderListing();
            throw new IllegalStateException("フォルダ作成後、一覧に現れませんでした: " + folderName);
        }
    }

    private boolean openNewFolderDialog() {
        dismissOverlays();
        if (!waitForNewItemMenuButtonReady()) {
            log.info("新規メニューボタンが見つかりません");
            return false;
        }
        Locator button = newItemMenuButton();
        if (button.count() == 0 || isInSidebar(button)) {
            return false;
        }

        log.info("新規メニューからフォルダ作成を選びます");
        button.click();
        Locator folderItem = page.locator("[data-target-id='MenuItem-newfolder']").last();
        try {
            folderItem.waitFor(new Locator.WaitForOptions().setTimeout(5000));
        } catch (Exception e) {
            log.warn("フォルダ作成メニューが表示されませんでした");
            dismissOverlays();
            return false;
        }
        log.info("フォルダ作成メニューをクリック: MenuItem-newfolder");
        folderItem.click();
        return waitForNewFolderNameInput(8000);
    }

    private boolean submitNewFolderName(String folderName) {
        if (!waitForNewFolderNameInput(5000)) {
            log.warn("フォルダ名入力欄が見つかりません");
            logCreateFolderDialogDump();
            return false;
        }
        Locator input = folderNameInput();
        log.info("フォルダ名を入力します: " + folderName);
        if (!fillFolderName(input, folderName)) {
            log.warn("フォルダ名を入力できませんでした。入力値=" + safeInputValue(input));
            logCreateFolderDialogDump();
            return false;
        }

        Locator dialog = createFolderDialog();
        if (dialog.count() > 0 && dialog.isVisible()) {
            Locator createButton = dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("作成"))
                .or(dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Create")))
                .first();
            if (createButton.count() > 0 && createButton.isVisible()) {
                log.info("作成ボタンをクリックします");
                createButton.click();
            } else {
                log.info("作成ボタンが無いため Enter で確定します");
                input.press("Enter");
            }
            try {
                dialog.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN)
                    .setTimeout(10000));
            } catch (Exception e) {
                log.warn("フォルダ作成ダイアログが閉じませんでした。入力値=" + safeInputValue(input));
                logCreateFolderDialogDump();
                return false;
            }
        } else {
            log.info("インライン入力のため Enter で確定します");
            input.press("Enter");
            page.waitForTimeout(800);
        }
        page.waitForTimeout(800);
        return true;
    }

    private boolean fillFolderName(Locator input, String folderName) {
        input.click();
        input.fill(folderName);
        if (folderName.equals(safeInputValue(input))) {
            return true;
        }
        log.info("fill が反映されなかったためキーボード入力します");
        input.click();
        page.keyboard().press("Control+A");
        page.keyboard().press("Backspace");
        input.pressSequentially(folderName);
        return folderName.equals(safeInputValue(input));
    }

    private boolean waitForNewFolderNameInput(int timeoutMs) {
        try {
            folderNameInput().waitFor(new Locator.WaitForOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Locator createFolderDialog() {
        return page.locator("[data-testid='modal-content-container'], .CreateFolderModalV2, [role='dialog']")
            .filter(new Locator.FilterOptions().setHas(page.locator("input[name='folder-name']")))
            .last();
    }

    private Locator folderNameInput() {
        return page.locator("input[name='folder-name']")
            .or(page.locator("[data-testid='modal-content-container'] input[type='text']").first())
            .or(page.getByRole(AriaRole.DIALOG).locator("input[type='text']").first())
            .or(page.locator("[role='row'] input[type='text']").last())
            .first();
    }

    private void logCreateFolderDialogDump() {
        try {
            Object dump = page.evaluate("""
                () => {
                  const dialog = document.querySelector('[data-testid="modal-content-container"], [role="dialog"]');
                  const inputs = [...document.querySelectorAll('input, textarea, [role="textbox"]')]
                    .filter(el => el.offsetWidth || el.offsetHeight)
                    .map(el => ({
                      name: el.getAttribute('name'),
                      type: el.getAttribute('type'),
                      placeholder: el.getAttribute('placeholder'),
                      value: el.value,
                      focused: el === document.activeElement
                    }));
                  return {
                    dialogText: dialog ? (dialog.innerText || '').slice(0, 400) : '',
                    focused: document.activeElement && document.activeElement.tagName,
                    inputs
                  };
                }
                """);
            log.info("フォルダ作成ダイアログ状態: " + dump);
        } catch (Exception e) {
            log.warn("フォルダ作成ダイアログのダンプに失敗しました: " + e.getMessage());
        }
    }

    private static String safeInputValue(Locator input) {
        try {
            String value = input.inputValue();
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private void logFolderListing() {
        Locator rows = page.locator("[role='row']");
        int count = rows.count();
        log.info("Box 一覧: " + count + " 行");
        int max = Math.min(count, 30);
        for (int i = 0; i < max; i++) {
            Locator row = rows.nth(i);
            log.info("  row[" + i + "] name=" + extractFileName(row) + " folder=" + isFolderRow(row));
        }
    }

    private boolean waitUntilFolderListed(String folderName, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (folderListed(folderName)) {
                return true;
            }
            page.waitForTimeout(400);
        }
        return folderListed(folderName);
    }

    private boolean folderListed(String folderName) {
        return findFolderRow(folderName) != null;
    }

    private Locator findFolderRow(String folderName) {
        Locator rows = page.locator("[role='row']");
        int count = rows.count();
        Locator named = null;
        for (int i = 0; i < count; i++) {
            Locator row = rows.nth(i);
            String name = extractFileName(row);
            if (!folderName.equals(name)) {
                continue;
            }
            if (isFolderRow(row)) {
                return row;
            }
            named = row;
        }
        return named;
    }

    private Locator findFileRow(String fileName) {
        Locator rows = page.locator("[role='row']");
        int count = rows.count();
        Locator named = null;
        for (int i = 0; i < count; i++) {
            Locator row = rows.nth(i);
            String name = extractFileName(row);
            if (!sameItemName(fileName, name)) {
                continue;
            }
            if (!isFolderRow(row)) {
                return row;
            }
            named = row;
        }
        return named;
    }

    private static boolean sameItemName(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        if (expected.equals(actual)) {
            return true;
        }
        return expected.replaceAll("\\s+", " ").equals(actual.replaceAll("\\s+", " "));
    }

    private void downloadFile(String fileName, Path destination) {
        withTimeoutRetry("ダウンロード: " + fileName, () -> {
            downloadFileOnce(fileName, destination);
            return null;
        });
    }

    private void downloadFileOnce(String fileName, Path destination) {
        ensureFolderView();
        Locator row = waitForFileRow(fileName);
        log.info("Box からダウンロードします: " + fileName);
        Download download = page.waitForDownload(
            new Page.WaitForDownloadOptions().setTimeout(config.getBrowserTimeoutMs()),
            () -> triggerDownload(row, fileName)
        );
        try {
            Files.createDirectories(destination.getParent());
            download.saveAs(destination);
        } catch (IOException e) {
            throw new IllegalStateException("ダウンロード先に保存できませんでした: " + destination, e);
        }
        log.info("ダウンロード完了: " + destination.toAbsolutePath());
        ensureFolderView();
    }

    private Locator waitForFileRow(String fileName) {
        long deadline = System.currentTimeMillis() + config.getBrowserTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            Locator row = findFileRow(fileName);
            if (row != null) {
                return row;
            }
            page.waitForTimeout(400);
        }
        logFolderListing();
        throw new IllegalStateException("Box にファイルが見つかりません: " + fileName);
    }

    private void triggerDownload(Locator row, String fileName) {
        openContextMenu(row);
        if (clickDownloadMenuItem()) {
            return;
        }
        dismissOverlays();
        selectFile(fileName);
        clickToolbarAction(List.of("Download", "ダウンロード"));
    }

    private boolean clickDownloadMenuItem() {
        Locator byId = page.locator("[data-target-id='MenuItem-download'], [data-target-id='MenuItem-Download']").last();
        if (byId.count() > 0 && byId.isVisible()) {
            byId.click();
            page.waitForTimeout(500);
            return true;
        }
        return clickMenuItem(List.of("Download", "ダウンロード"));
    }

    private void openContextMenu(Locator row) {
        dismissOverlays();
        row.scrollIntoViewIfNeeded();
        row.click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));
        page.waitForTimeout(400);
    }

    private void selectFile(String fileName) {
        ensureFolderView();
        dismissOverlays();
        Locator row = waitForFileRow(fileName);
        row.scrollIntoViewIfNeeded();
        Locator checkbox = row.locator(
            "input[type='checkbox'], [role='checkbox'], "
            + "button[aria-label*='Select'], button[aria-label*='選択'], "
            + "[data-testid*='checkbox'], [data-resin-target='select']"
        ).first();
        if (checkbox.count() > 0) {
            checkbox.click(new Locator.ClickOptions().setForce(true));
        } else {
            row.click(new Locator.ClickOptions().setPosition(12, 15).setForce(true));
        }
        page.waitForTimeout(500);
    }

    private void clickToolbarAction(List<String> labels) {
        for (String label : labels) {
            Locator button = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(label));
            if (button.count() > 0) {
                button.first().click();
                return;
            }
            Locator menuItem = page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName(label));
            if (menuItem.count() > 0) {
                menuItem.first().click();
                return;
            }
        }

        Locator moreButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("More Options"))
            .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("その他のオプション")))
            .or(page.locator("[aria-label='More Options'], [aria-label='その他のオプション']"))
            .first();
        if (moreButton.count() == 0) {
            throw new IllegalStateException("操作ボタンが見つかりません: " + labels);
        }
        moreButton.click();
        page.waitForTimeout(300);
        for (String label : labels) {
            Locator menuItem = page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName(label));
            if (menuItem.count() > 0) {
                menuItem.first().click();
                return;
            }
        }
        throw new IllegalStateException("メニュー項目が見つかりません: " + labels);
    }

    private static boolean isCreateFolderMenuItem(String text, String targetId, String testId) {
        String id = ((targetId == null ? "" : targetId) + " " + (testId == null ? "" : testId)).toLowerCase();
        if (id.contains("upload")) {
            return false;
        }
        if (id.contains("createfolder") || id.contains("newfolder") || id.contains("create-folder") || id.contains("new-folder")) {
            return true;
        }
        String t = text == null ? "" : text.replace(" ", "").replace("\n", "").replace("\r", "");
        if (t.contains("アップロード") || t.toLowerCase().contains("upload")
            || t.contains("ファイル") || t.toLowerCase().contains("file")) {
            return false;
        }
        return t.equals("フォルダ") || t.equals("新規フォルダ") || t.equals("Folder") || t.equals("NewFolder")
            || t.contains("フォルダを作成") || t.toLowerCase().contains("createfolder");
    }

    private static String safeInnerText(Locator item) {
        try {
            String text = item.innerText();
            return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String safeAttr(Locator item, String name) {
        try {
            String value = item.getAttribute(name);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private void guardTargetFolder(String action) {
        if (nestedFolderUpload) {
            return;
        }
        String currentUrl = page.url();
        if (isOnTargetFolder(currentUrl)) {
            return;
        }

        log.info("フォルダが変わりました (" + action + "): " + currentUrl + " -> " + activeFolderUrl);
        navigateToFolderUrl(activeFolderUrl);
        waitForFolderUrl(extractFolderId(activeFolderUrl));
        logCurrentFolderState();
    }

    private void waitForUploadComplete(String fileName, boolean uploadTriggered) {
        log.info("アップロード完了待ち: " + fileName + " (最大 "
            + (config.getUploadCompleteTimeoutMs() / 1000) + " 秒)");
        long startTime = System.currentTimeMillis();
        long deadline = startTime + config.getUploadCompleteTimeoutMs();
        boolean uploadStarted = uploadTriggered;
        int refreshAttempts = 0;
        int logIntervalSeconds = 10;
        long lastLogTime = startTime;

        while (System.currentTimeMillis() < deadline) {
            handleUploadConflictDialogs();

            if (isFileListedInFolder(fileName)) {
                log.info("アップロード完了を確認: " + fileName);
                page.waitForTimeout(1000);
                return;
            }

            if (hasUploadInProgress()) {
                if (!uploadStarted) {
                    log.info("アップロード進行中を検出しました");
                }
                uploadStarted = true;
                page.waitForTimeout(3000);
                continue;
            }

            long elapsedMs = System.currentTimeMillis() - startTime;
            if (elapsedMs - lastLogTime >= logIntervalSeconds * 1000L) {
                log.info("アップロード完了待機中... (" + (elapsedMs / 1000) + " 秒経過)");
                lastLogTime = elapsedMs;
            }

            if (uploadStarted && elapsedMs >= 20000 && refreshAttempts < 2) {
                refreshAttempts++;
                log.info("ファイル一覧を更新します (" + refreshAttempts + ")");
                refreshFolderListing();
                page.waitForTimeout(3000);
                if (isFileListedInFolder(fileName)) {
                    log.info("アップロード完了を確認: " + fileName);
                    return;
                }
            }

            page.waitForTimeout(2000);
        }

        throw new IllegalStateException("アップロード完了を確認できませんでした: " + fileName);
    }

    private void refreshFolderListing() {
        navigateToFolderUrl(activeFolderUrl);
        waitForFolderUrl(extractFolderId(activeFolderUrl));
        waitForBoxPageLoad();
    }

    private boolean hasUploadInProgress() {
        if (page.locator(
            "[data-resin-target='uploadActivity'], [class*='UploadProgress'], [class*='upload-progress'], "
            + "[aria-label*='Uploading'], [aria-label*='アップロード中'], "
            + "[data-testid='upload-progress'], [class*='UploadActivity']"
        ).count() > 0) {
            return true;
        }

        return page.getByText("アップロード中").count() > 0
            || page.getByText("Uploading").count() > 0;
    }

    private void handleUploadConflictDialogs() {
        List<String> buttonLabels = List.of(
            "Replace", "上書き", "Upload as new version", "新しいバージョンとしてアップロード",
            "Continue", "続行", "OK", "Replace existing file", "既存のファイルを置き換える"
        );

        for (String label : buttonLabels) {
            Locator button = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(label)).first();
            if (button.count() > 0 && button.isVisible()) {
                log.info("アップロード確認ダイアログを処理: " + label);
                button.click();
                page.waitForTimeout(1000);
                return;
            }
        }
    }

    private boolean isFileListedInFolder(String fileName) {
        Locator rows = page.locator("[role='row']")
            .filter(new Locator.FilterOptions().setHasText(fileName));
        if (rows.count() > 0) {
            return true;
        }

        Locator names = page.locator("[data-testid='name'], [data-resin-target='name']")
            .filter(new Locator.FilterOptions().setHasText(fileName));
        if (names.count() > 0) {
            return true;
        }

        return page.getByText(fileName, new Page.GetByTextOptions().setExact(true)).count() > 0;
    }

    private void openBrowser() throws Exception {
        playwright = Playwright.create();
        connectOverCdp();
    }

    private void connectOverCdp() throws Exception {
        log.info("CDP 接続: " + config.getBrowserCdpUrl());

        BrowserType.ConnectOverCDPOptions options = new BrowserType.ConnectOverCDPOptions()
            .setSlowMo(config.getBrowserSlowMo())
            .setTimeout(config.getBrowserTimeoutMs());

        int maxAttempts = 3;
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                browser = playwright.chromium().connectOverCDP(config.getBrowserCdpUrl(), options);
                connectedViaCdp = true;
                break;
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    log.warn("CDP 接続失敗 (" + attempt + "/" + maxAttempts + ")。2秒後に再試行します...");
                    Thread.sleep(2000);
                }
            }
        }

        if (!connectedViaCdp) {
            throw new IllegalStateException(
                "Edge への CDP 接続に失敗しました: " + config.getBrowserCdpUrl() + "\n"
                + "先に scripts/start-edge-cdp.bat を実行し、Edge で Box にログインしてください。\n"
                + "詳細: " + (lastError != null ? lastError.getMessage() : "不明"),
                lastError
            );
        }

        if (browser.contexts().isEmpty()) {
            throw new IllegalStateException("CDP 接続後にブラウザコンテキストが見つかりません");
        }

        context = browser.contexts().get(0);
        page = selectBestPage(context);
        page.setDefaultTimeout(config.getBrowserTimeoutMs());
        bringPageToFront();
        log.info("CDP 接続成功 (タブ数: " + context.pages().size() + ", 使用中 URL: " + page.url() + ")");
    }

    private Page selectBestPage(BrowserContext browserContext) {
        for (Page existingPage : browserContext.pages()) {
            if (isOnTargetFolder(existingPage.url())) {
                return existingPage;
            }
        }

        for (Page existingPage : browserContext.pages()) {
            if (isBoxAppUrl(existingPage.url())) {
                return existingPage;
            }
        }
        return browserContext.pages().isEmpty() ? browserContext.newPage() : browserContext.pages().get(0);
    }

    private void bringPageToFront() {
        try {
            page.bringToFront();
        } catch (Exception ignored) {
        }
    }

    private boolean isBoxAppUrl(String url) {
        String host = Config.hostOf(url);
        if (host == null) {
            return false;
        }
        String enterprise = config.getBoxEnterpriseDomain();
        if (enterprise != null && host.equalsIgnoreCase(enterprise)) {
            return true;
        }
        return host.equals("box.com") || host.endsWith(".box.com");
    }

    private void ensureLoggedIn() {
        if (isLoggedIn()) {
            log.info("Box ログイン済み（現在のタブ）");
            gotoFolder();
            waitForBoxPageLoad();
            return;
        }

        gotoFolder();
        waitForBoxPageLoad();

        if (isLoggedIn()) {
            log.info("Box ログイン済み");
            return;
        }

        log.info("Box 未ログインです。開いたブラウザ上で手動ログインしてください（最大 "
            + config.getBoxLoginWaitSeconds() + " 秒）");
        log.info("現在の URL: " + page.url());

        long deadline = System.currentTimeMillis() + (config.getBoxLoginWaitSeconds() * 1000L);
        while (System.currentTimeMillis() < deadline) {
            page.waitForTimeout(3000);
            if (isLoggedIn()) {
                gotoFolder();
                waitForBoxPageLoad();
                log.info("Box ログインを確認しました");
                return;
            }
        }
        throw new IllegalStateException("Box へのログインがタイムアウトしました。URL: " + page.url());
    }

    private void waitForBoxPageLoad() {
        long deadline = System.currentTimeMillis() + config.getBrowserTimeoutMs();

        while (System.currentTimeMillis() < deadline) {
            ensureFolderAccessible();

            String targetFolderId = extractFolderId(activeFolderUrl);
            if (isOnTargetFolder(page.url()) && isUiShowingTargetFolder(targetFolderId) && hasBoxExplorerUi()) {
                log.info("Box ページ準備完了");
                return;
            }

            if (hasBoxExplorerUi() && !isOnTargetFolder(page.url())) {
                gotoFolder();
            }

            try {
                page.waitForURL("**" + config.getBoxEnterpriseDomain() + "/**", new Page.WaitForURLOptions().setTimeout(3000));
            } catch (Exception ignored) {
            }

            page.waitForTimeout(1000);
        }

        if (hasBoxExplorerUi()) {
            log.warn("Box ページ要素は表示されていますが、対象フォルダの確認に失敗しました");
            return;
        }

        List<String> selectors = List.of(
            "[data-resin-target=\"fileList\"]",
            "[data-testid=\"browse-content-pane\"]",
            "[data-resin-target=\"header\"]"
        );

        for (String selector : selectors) {
            try {
                page.locator(selector).first().waitFor(new Locator.WaitForOptions().setTimeout(3000));
                log.info("Box ページ準備完了");
                return;
            } catch (Exception ignored) {
            }
        }

        log.info("Box ページ要素の待機をスキップして続行します");
    }

    private void gotoFolder() {
        String targetFolderId = extractFolderId(activeFolderUrl);
        if (isOnTargetFolder(page.url()) && isUiShowingTargetFolder(targetFolderId)) {
            ensureFolderAccessible();
            logCurrentFolderState();
            return;
        }

        log.info("対象フォルダへ移動: " + activeFolderUrl + " (現在: " + page.url() + ")");
        navigateToFolderUrl(activeFolderUrl);
        waitForFolderUrl(targetFolderId);
        ensureFolderAccessible();
        logCurrentFolderState();
    }

    private void ensureFolderAccessible() {
        if (!isFolderNotFoundPage()) {
            return;
        }

        throw new IllegalStateException(
            "Box フォルダが見つかりません（" + activeFolderUrl + "）。"
            + "box.folder.id / box.folder.url の設定を確認してください。"
        );
    }

    private boolean isFolderNotFoundPage() {
        if (page.getByText("お探しのページが見つかりません").count() > 0) {
            return true;
        }

        if (page.getByText("The page you're looking for couldn't be found").count() > 0) {
            return true;
        }

        if (page.getByText("Page not found").count() > 0) {
            return true;
        }

        Locator body = page.locator("body");
        if (body.count() == 0) {
            return false;
        }

        String bodyText = body.innerText();
        return bodyText != null && (
            bodyText.contains("お探しのページが見つかりません")
            || bodyText.contains("The page you're looking for couldn't be found")
        );
    }

    private void navigateToFolderUrl(String folderUrl) {
        bringPageToFront();
        try {
            page.navigate(
                folderUrl,
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD)
            );
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && message.contains("\n")) {
                message = message.substring(0, message.indexOf('\n'));
            }
            log.warn("ナビゲーション警告: " + message);
        }

        page.waitForTimeout(2000);
        bringPageToFront();

        if (!isOnTargetFolder(page.url())) {
            try {
                page.evaluate("url => { window.location.href = url; }", folderUrl);
                page.waitForTimeout(3000);
                bringPageToFront();
            } catch (Exception e) {
                log.warn("location.href による遷移失敗: " + e.getMessage());
            }
        }
    }

    private void waitForFolderUrl(String targetFolderId) {
        if (targetFolderId == null) {
            return;
        }

        try {
            page.waitForURL(
                "**/folder/" + targetFolderId + "**",
                new Page.WaitForURLOptions().setTimeout(15000)
            );
        } catch (Exception e) {
            log.warn("フォルダ URL 待機タイムアウト: " + page.url());
        }

        page.waitForTimeout(2000);
    }

    private void ensureOnTargetFolder() {
        gotoFolder();
        waitForBoxPageLoad();
        ensureFolderAccessible();
        page.waitForTimeout(1000);

        if (!isOnTargetFolder(page.url())) {
            throw new IllegalStateException(
                "対象フォルダに移動できませんでした。期待: " + activeFolderUrl + " / 現在: " + page.url()
            );
        }

        logCurrentFolderState();
    }

    private void navigateToFolder() {
        ensureOnTargetFolder();
    }

    private boolean openFolderFromListing(String folderName) {
        dismissOverlays();
        ensureFolderView();
        page.waitForTimeout(500);

        Locator row = findFolderRow(folderName);
        if (row != null) {
            String beforeUrl = page.url();
            row.scrollIntoViewIfNeeded();
            Locator nameLink = row.locator("[data-testid='name'], [data-resin-target='name'], a").first();
            if (nameLink.count() > 0) {
                nameLink.dblclick();
            } else {
                row.dblclick();
            }
            waitForEnteredFolder(folderName, beforeUrl);
            logCurrentFolderState();
            return true;
        }

        Locator folderNameCell = page.locator("[data-testid='name'], [data-resin-target='name']")
            .filter(new Locator.FilterOptions().setHasText(folderName))
            .first();
        if (folderNameCell.count() > 0) {
            String beforeUrl = page.url();
            folderNameCell.dblclick();
            waitForEnteredFolder(folderName, beforeUrl);
            logCurrentFolderState();
            return true;
        }

        log.info("一覧からフォルダが見つかりません: " + folderName);
        return false;
    }

    private void waitForEnteredFolder(String folderName, String beforeUrl) {
        long deadline = System.currentTimeMillis() + config.getBrowserTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            if (isCurrentFolderNamed(folderName)) {
                return;
            }
            String url = page.url();
            if (url != null && beforeUrl != null && !url.equals(beforeUrl) && url.contains("/folder/")) {
                page.waitForTimeout(500);
                if (isCurrentFolderNamed(folderName) || extractFolderId(url) != null) {
                    return;
                }
            }
            page.waitForTimeout(300);
        }
        log.warn("フォルダ表示の確認がタイムアウトしました: " + folderName + " / " + describeCurrentFolderState());
    }

    private boolean isUiShowingTargetFolder(String targetFolderId) {
        return targetFolderId == null || isOnTargetFolder(page.url());
    }

    private boolean isBreadcrumbShowingFolderId(String folderId) {
        Locator breadcrumbLink = page.locator(
            "[data-resin-target='breadcrumb'] a[href*='/folder/" + folderId + "'], "
            + "nav a[href*='/folder/" + folderId + "'], "
            + "[class*='breadcrumb'] a[href*='/folder/" + folderId + "']"
        ).last();
        return breadcrumbLink.count() > 0;
    }

    private boolean isCurrentFolderNamed(String folderName) {
        String current = getCurrentFolderNameFromUi();
        return current != null && current.trim().equals(folderName.trim());
    }

    private String getCurrentFolderNameFromUi() {
        List<String> selectors = List.of(
            "[data-resin-target='breadcrumb'] li:last-child",
            "[data-resin-target='breadcrumb'] a:last-child",
            "[data-testid='page-header-title']",
            "[class*='Breadcrumb'] li:last-child",
            "[data-resin-target='pageHeader'] h1",
            "header h1"
        );

        for (String selector : selectors) {
            Locator element = page.locator(selector).first();
            if (element.count() > 0) {
                String text = element.innerText();
                if (text != null && !text.trim().isEmpty()) {
                    return text.trim();
                }
            }
        }

        return null;
    }

    private void logCurrentFolderState() {
        log.info("現在の Box 表示: " + describeCurrentFolderState());
    }

    private String describeCurrentFolderState() {
        String folderName = getCurrentFolderNameFromUi();
        if (folderName == null || folderName.isBlank()) {
            return "URL=" + page.url();
        }
        return folderName + " (URL=" + page.url() + ")";
    }

    private boolean isOnTargetFolder(String currentUrl) {
        String targetFolderId = extractFolderId(activeFolderUrl);
        return targetFolderId != null
            && currentUrl != null
            && currentUrl.contains("/folder/" + targetFolderId);
    }

    private String extractFolderId(String url) {
        if (url == null) {
            return null;
        }
        int index = url.indexOf("/folder/");
        if (index < 0) {
            return null;
        }
        String remainder = url.substring(index + "/folder/".length());
        int slash = remainder.indexOf('/');
        int query = remainder.indexOf('?');
        int end = remainder.length();
        if (slash >= 0) {
            end = Math.min(end, slash);
        }
        if (query >= 0) {
            end = Math.min(end, query);
        }
        return remainder.substring(0, end);
    }

    private boolean isLoggedIn() {
        String url = page.url();
        if (url == null || url.isBlank() || "about:blank".equals(url)) {
            return false;
        }

        String lowerUrl = url.toLowerCase();

        if (lowerUrl.contains("account.box.com/login")
            || lowerUrl.contains("/login")
            || lowerUrl.contains("/signin")
            || lowerUrl.contains("oauth2/authorize")
            || lowerUrl.contains("login.microsoftonline.com")
            || lowerUrl.contains("okta.com")) {
            return false;
        }

        if (page.locator("input[type='password'], #login-email, [data-testid='login-form']").count() > 0) {
            return false;
        }

        if (isBoxAppUrl(url)) {
            if (lowerUrl.contains("/folder/") || lowerUrl.contains("/file/")) {
                return true;
            }

            if (hasBoxExplorerUi()) {
                return true;
            }
        }

        return hasBoxExplorerUi();
    }

    private boolean hasBoxExplorerUi() {
        if (page.locator(
            "[data-resin-target=\"fileList\"], [data-testid=\"browse-content-pane\"], "
            + "[role=\"grid\"], [data-resin-target=\"header\"]"
        ).count() > 0) {
            return true;
        }

        for (String label : List.of("Upload", "アップロード", "New", "新規")) {
            if (page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(label)).count() > 0) {
                return true;
            }
        }

        return false;
    }

    private String extractFileName(Locator row) {
        List<String> selectors = List.of(
            "[data-testid=\"name\"]",
            "[data-resin-target=\"name\"]",
            ".item-name",
            "a[aria-label]"
        );

        for (String selector : selectors) {
            Locator nameLocator = row.locator(selector).first();
            if (nameLocator.count() > 0) {
                String text = nameLocator.innerText();
                if (text != null && !text.trim().isEmpty()) {
                    return text.trim();
                }
                String aria = nameLocator.getAttribute("aria-label");
                if (aria != null && !aria.trim().isEmpty()) {
                    return aria.trim();
                }
            }
        }

        String rowText = row.innerText();
        if (rowText == null || rowText.trim().isEmpty()) {
            return null;
        }
        return rowText.split("\n")[0].trim();
    }

    private boolean isFolderRow(Locator row) {
        return row.locator("[data-resin-target=\"folder\"], [aria-label*=\"folder\"], [data-testid=\"folder\"]").count() > 0;
    }

    private <T> T withTimeoutRetry(String actionName, Supplier<T> action) {
        int maxRetries = config.getBrowserTimeoutRetryCount();
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!isPlaywrightTimeout(e) || attempt >= maxRetries) {
                    throw e;
                }
                attempt++;
                log.warn(
                    "{} で Playwright タイムアウトが発生しました。リロードして再検索します ({}/{})",
                    actionName, attempt, maxRetries
                );
                reloadAndReSearchElements();
            }
        }
    }

    private static boolean isPlaywrightTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutError) {
                return true;
            }
            if (current instanceof PlaywrightException) {
                String message = current.getMessage();
                if (message != null && message.toLowerCase().contains("timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void reloadAndReSearchElements() {
        dismissOverlays();
        if (activeFolderUrl != null && !activeFolderUrl.isBlank()) {
            log.info("対象フォルダをリロードします: {}", activeFolderUrl);
            refreshFolderListing();
        } else {
            log.info("ページをリロードします");
            page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.LOAD));
            waitForBoxPageLoad();
        }
        ensureFolderView();
    }

    private boolean clickMenuItem(List<String> labels) {
        for (String label : labels) {
            Locator menu = page.locator(
                "[role='menu'], [role='listbox'], [data-testid*='dropdown'], [class*='dropdown'], [class*='menu']"
            ).last();
            Locator menuItem = menu.locator("[role='menuitem'], [role='option']")
                .filter(new Locator.FilterOptions().setHasText(label))
                .first();
            if (menuItem.count() == 0) {
                menuItem = page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName(label))
                    .or(page.locator("[role=\"menuitem\"]").filter(new Locator.FilterOptions().setHasText(label)))
                    .first();
            }
            if (menuItem.count() > 0 && menuItem.isVisible()) {
                menuItem.click();
                page.waitForTimeout(500);
                return true;
            }
        }
        return false;
    }

    private void dismissOverlays() {
        page.keyboard().press("Escape");
        page.waitForTimeout(200);
        page.keyboard().press("Escape");
        page.waitForTimeout(200);
    }

    private void ensureFolderView() {
        String url = page.url();
        boolean inPreview = (url.contains("/file/") && !url.contains("/folder/"))
            || page.locator("[data-testid=\"preview-header\"], [class*=\"PreviewHeader\"], [data-testid=\"archive-preview\"]").count() > 0;

        if (!inPreview) {
            return;
        }

        log.info("ZIP プレビューを検出。フォルダ一覧に戻ります");
        Locator backButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Back"))
            .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("戻る")))
            .or(page.locator("[aria-label=\"Back\"], [aria-label=\"戻る\"]"))
            .first();

        if (backButton.count() > 0) {
            backButton.click();
            page.waitForTimeout(1500);
        } else {
            gotoFolder();
        }

        page.locator("[role=\"row\"], [data-testid=\"browse-content-pane\"]").first().waitFor();
    }

    @Override
    public void close() {
        if (!connectedViaCdp) {
            try {
                if (context != null) {
                    context.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (browser != null) {
                    browser.close();
                }
            } catch (Exception ignored) {
            }
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignored) {
        }
    }
}
