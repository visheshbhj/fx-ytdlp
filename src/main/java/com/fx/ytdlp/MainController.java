package com.fx.ytdlp;

import com.fx.ytdlp.ytdlp.AdvancedOptions;
import com.fx.ytdlp.ytdlp.Cover;
import com.fx.ytdlp.ytdlp.DownloadJob;
import com.fx.ytdlp.ytdlp.DownloadQueue;
import com.fx.ytdlp.ytdlp.Ffmpeg;
import com.fx.ytdlp.ytdlp.HttpDownload;
import com.fx.ytdlp.ytdlp.Os;
import com.fx.ytdlp.ytdlp.Preset;
import com.fx.ytdlp.ytdlp.YtDlpBinary;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.stage.DirectoryChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class MainController {
    @FXML private TextField urlField;
    @FXML private ComboBox<Preset> presetBox;
    @FXML private ComboBox<Cover> coverBox;
    @FXML private CheckBox playlistCheck;
    @FXML private Label outputDirLabel;
    @FXML private TableView<DownloadJob> table;
    @FXML private TableColumn<DownloadJob, String> titleCol;
    @FXML private TableColumn<DownloadJob, Preset> presetCol;
    @FXML private TableColumn<DownloadJob, Double> progressCol;
    @FXML private TableColumn<DownloadJob, DownloadJob.Status> statusCol;
    @FXML private TableColumn<DownloadJob, String> detailCol;
    @FXML private TitledPane advancedPane;
    @FXML private CheckBox subtitlesCheck;
    @FXML private TextField subLangsField;
    @FXML private CheckBox autoSubsCheck;
    @FXML private ComboBox<AdvancedOptions.SponsorBlock> sponsorBlockBox;
    @FXML private CheckBox chaptersCheck;
    @FXML private CheckBox skipDownloadedCheck;
    @FXML private TextField speedLimitField;
    @FXML private ComboBox<String> cookiesBox;
    @FXML private TextField templateField;
    @FXML private TextField extraArgsField;
    @FXML private Label versionLabel;
    @FXML private Button updateButton;
    @FXML private ProgressBar toolProgress;

    private final Settings settings = new Settings();
    private final YtDlpBinary binary = new YtDlpBinary();
    private final Ffmpeg ffmpeg = new Ffmpeg();
    private final DownloadQueue queue = new DownloadQueue(binary, ffmpeg);
    private final ObservableList<DownloadJob> jobs = FXCollections.observableArrayList();
    private Path outputDir;

    @FXML
    private void initialize() {
        presetBox.getItems().setAll(Preset.values());
        presetBox.setValue(settings.preset());
        coverBox.getItems().setAll(Cover.values());
        coverBox.setValue(settings.cover());
        playlistCheck.setSelected(settings.playlist());
        setOutputDir(settings.outputDir());
        initAdvanced();

        presetBox.valueProperty().addListener((o, a, v) -> settings.preset(v));
        coverBox.valueProperty().addListener((o, a, v) -> settings.cover(v));
        playlistCheck.selectedProperty().addListener((o, a, v) -> settings.playlist(v));

        titleCol.setCellValueFactory(c -> c.getValue().titleProperty());
        presetCol.setCellValueFactory(c -> Bindings.createObjectBinding(c.getValue()::preset));
        progressCol.setCellValueFactory(c -> c.getValue().progressProperty().asObject());
        progressCol.setCellFactory(ProgressBarTableCell.forTableColumn());
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        detailCol.setCellValueFactory(c -> c.getValue().detailProperty());
        table.setItems(jobs);
        table.setRowFactory(t -> createRow());

        ensureTools();
    }

    void shutdown() {
        settings.advanced(readAdvanced());
        queue.shutdown();
    }

    private void initAdvanced() {
        sponsorBlockBox.getItems().setAll(AdvancedOptions.SponsorBlock.values());
        cookiesBox.getItems().setAll(AdvancedOptions.BROWSERS);
        cookiesBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(String s) {
                return s == null || s.isEmpty() ? "None" : s;
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        subLangsField.disableProperty().bind(subtitlesCheck.selectedProperty().not());
        autoSubsCheck.disableProperty().bind(subtitlesCheck.selectedProperty().not());

        AdvancedOptions o = settings.advanced();
        subtitlesCheck.setSelected(o.subtitles());
        subLangsField.setText(o.subtitleLangs());
        autoSubsCheck.setSelected(o.autoSubtitles());
        sponsorBlockBox.setValue(o.sponsorBlock());
        chaptersCheck.setSelected(o.embedChapters());
        skipDownloadedCheck.setSelected(o.skipDownloaded());
        speedLimitField.setText(o.speedLimit());
        cookiesBox.setValue(o.cookiesBrowser());
        templateField.setText(o.outputTemplate());
        extraArgsField.setText(o.extraArgs());

        advancedPane.setExpanded(settings.advancedExpanded());
        advancedPane.expandedProperty().addListener((obs, a, v) -> settings.advancedExpanded(v));
    }

    private AdvancedOptions readAdvanced() {
        return new AdvancedOptions(
                subtitlesCheck.isSelected(),
                subLangsField.getText(),
                autoSubsCheck.isSelected(),
                sponsorBlockBox.getValue(),
                chaptersCheck.isSelected(),
                skipDownloadedCheck.isSelected(),
                speedLimitField.getText(),
                cookiesBox.getValue() == null ? "" : cookiesBox.getValue(),
                templateField.getText(),
                extraArgsField.getText());
    }

    @FXML
    private void onResetTemplate() {
        templateField.setText(AdvancedOptions.DEFAULT_TEMPLATE);
    }

    @FXML
    private void onAdd() {
        String text = urlField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        AdvancedOptions options = readAdvanced();
        String problem = options.validate();
        if (problem != null) {
            advancedPane.setExpanded(true);
            Alert alert = new Alert(Alert.AlertType.WARNING, problem);
            alert.setHeaderText("Check the advanced options");
            alert.show();
            return;
        }
        settings.advanced(options);
        for (String url : text.split("\\s+")) {
            enqueue(new DownloadJob(url, presetBox.getValue(), coverBox.getValue(),
                    playlistCheck.isSelected(), outputDir, options));
        }
        urlField.clear();
    }

    @FXML
    private void onChooseDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        if (Files.isDirectory(outputDir)) {
            chooser.setInitialDirectory(outputDir.toFile());
        }
        File dir = chooser.showDialog(table.getScene().getWindow());
        if (dir != null) {
            setOutputDir(dir.toPath());
            settings.outputDir(outputDir);
        }
    }

    @FXML
    private void onUpdate() {
        updateButton.setDisable(true);
        showBusy(queue.busy() ? "Waiting for the current download to finish…" : "Checking for updates…");
        background(() -> queue.betweenJobs(() -> {
            showBusy("Checking for updates…");
            String updated = binary.update(progressFor("yt-dlp"));
            String note = updated == null ? "up to date" : "updated";
            if (ffmpeg.find().isEmpty() && ffmpeg.canInstall()) {
                ffmpeg.install(progressFor("ffmpeg"));
                note += ", ffmpeg installed";
            }
            return new String[] {binary.version(), note};
        }), result -> {
            hideProgress();
            showVersion(result[0]);
            versionLabel.setText(versionLabel.getText() + "  (" + result[1] + ")");
        }, e -> {
            hideProgress();
            showVersionFallback();
            error("Update failed", e);
        });
    }

    @FXML
    private void onClearFinished() {
        jobs.removeIf(DownloadJob::isFinished);
    }

    private void enqueue(DownloadJob job) {
        jobs.add(job);
        queue.submit(job);
    }

    private void setOutputDir(Path dir) {
        outputDir = dir;
        outputDirLabel.setText(dir.toString());
    }

    /** Downloads yt-dlp (and ffmpeg on Windows) on first run, then releases the queue. */
    private void ensureTools() {
        showBusy("Checking tools…");
        background(() -> {
            if (!binary.isInstalled()) {
                binary.install(progressFor("yt-dlp"));
            }
            if (ffmpeg.find().isEmpty() && ffmpeg.canInstall()) {
                ffmpeg.install(progressFor("ffmpeg (one-time)"));
            }
            return binary.version();
        }, version -> {
            hideProgress();
            showVersion(version);
            queue.toolsReady();
            if (ffmpeg.find().isEmpty()) {
                error("ffmpeg not found", new IOException(
                        "MP3/M4A conversion, merging video+audio and cover art need ffmpeg. "
                                + "Install it (e.g. sudo apt install ffmpeg) and restart."));
            }
        }, e -> {
            hideProgress();
            versionLabel.setText("yt-dlp: not available");
            queue.toolsReady();
            error("Could not set up tools in " + Os.binDir(), e);
        });
    }

    /** Progress callback for a tool download: fills the bar and shows MB done / total. */
    private HttpDownload.Progress progressFor(String what) {
        return (done, total) -> {
            String text = "Downloading " + what + ": " + (done >> 20)
                    + (total > 0 ? " / " + (total >> 20) : "") + " MB";
            double fraction = total > 0 ? (double) done / total : ProgressBar.INDETERMINATE_PROGRESS;
            Platform.runLater(() -> {
                showProgressBar(fraction);
                versionLabel.setText(text);
            });
        };
    }

    /** Shows a message with a spinning (indeterminate) bar; safe to call from any thread. */
    private void showBusy(String text) {
        Platform.runLater(() -> {
            showProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
            versionLabel.setText(text);
        });
    }

    private void showProgressBar(double progress) {
        toolProgress.setVisible(true);
        toolProgress.setManaged(true);
        toolProgress.setProgress(progress);
    }

    private void hideProgress() {
        toolProgress.setVisible(false);
        toolProgress.setManaged(false);
    }

    private void showVersion(String version) {
        versionLabel.setText("yt-dlp " + version
                + (ffmpeg.find().isPresent() ? " · ffmpeg ✓" : " · ffmpeg missing"));
        updateButton.setDisable(false);
    }

    private void showVersionFallback() {
        background(binary::version, this::showVersion, e -> versionLabel.setText("yt-dlp: unknown version"));
    }

    private TableRow<DownloadJob> createRow() {
        TableRow<DownloadJob> row = new TableRow<>();
        MenuItem cancel = new MenuItem("Cancel");
        cancel.setOnAction(e -> queue.cancel(row.getItem()));
        MenuItem retry = new MenuItem("Retry");
        retry.setOnAction(e -> {
            DownloadJob old = row.getItem();
            int idx = jobs.indexOf(old);
            DownloadJob fresh = old.copy();
            jobs.set(idx, fresh);
            queue.submit(fresh);
        });
        MenuItem open = new MenuItem("Open folder");
        open.setOnAction(e -> openFolder(row.getItem().outputDir()));
        MenuItem log = new MenuItem("Show log");
        log.setOnAction(e -> showLog(row.getItem()));
        MenuItem remove = new MenuItem("Remove");
        remove.setOnAction(e -> jobs.remove(row.getItem()));
        ContextMenu menu = new ContextMenu(cancel, retry, open, log, remove);
        menu.setOnShowing(e -> {
            boolean finished = row.getItem().isFinished();
            cancel.setDisable(finished);
            retry.setDisable(!finished);
            remove.setDisable(!finished);
        });
        row.contextMenuProperty().bind(Bindings.when(row.emptyProperty())
                .then((ContextMenu) null).otherwise(menu));
        return row;
    }

    private void showLog(DownloadJob job) {
        TextArea area = new TextArea(job.log());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefSize(800, 400);
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Log");
        alert.setHeaderText(job.titleProperty().get());
        alert.getDialogPane().setContent(area);
        alert.setResizable(true);
        alert.show();
    }

    private void openFolder(Path dir) {
        try {
            Os.openFolder(dir);
        } catch (IOException e) {
            error("Could not open " + dir, e);
        }
    }

    private static <T> void background(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> onError.accept(task.getException()));
        Thread t = new Thread(task, "yt-dlp-maintenance");
        t.setDaemon(true);
        t.start();
    }

    private static void error(String header, Throwable e) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage());
            alert.setHeaderText(header);
            alert.show();
        });
    }
}
