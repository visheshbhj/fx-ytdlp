package com.fx.ytdlp;

import com.fx.ytdlp.ytdlp.DownloadJob;
import com.fx.ytdlp.ytdlp.DownloadQueue;
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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ProgressBarTableCell;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class MainController {
    @FXML private TextField urlField;
    @FXML private ComboBox<Preset> presetBox;
    @FXML private CheckBox thumbnailCheck;
    @FXML private CheckBox playlistCheck;
    @FXML private Label outputDirLabel;
    @FXML private TableView<DownloadJob> table;
    @FXML private TableColumn<DownloadJob, String> titleCol;
    @FXML private TableColumn<DownloadJob, Preset> presetCol;
    @FXML private TableColumn<DownloadJob, Double> progressCol;
    @FXML private TableColumn<DownloadJob, DownloadJob.Status> statusCol;
    @FXML private TableColumn<DownloadJob, String> detailCol;
    @FXML private Label versionLabel;
    @FXML private Button updateButton;

    private final Settings settings = new Settings();
    private final YtDlpBinary binary = new YtDlpBinary();
    private final DownloadQueue queue = new DownloadQueue(binary);
    private final ObservableList<DownloadJob> jobs = FXCollections.observableArrayList();
    private Path outputDir;

    @FXML
    private void initialize() {
        presetBox.getItems().setAll(Preset.values());
        presetBox.setValue(settings.preset());
        thumbnailCheck.setSelected(settings.embedThumbnail());
        playlistCheck.setSelected(settings.playlist());
        setOutputDir(settings.outputDir());

        presetBox.valueProperty().addListener((o, a, v) -> settings.preset(v));
        thumbnailCheck.selectedProperty().addListener((o, a, v) -> settings.embedThumbnail(v));
        playlistCheck.selectedProperty().addListener((o, a, v) -> settings.playlist(v));

        titleCol.setCellValueFactory(c -> c.getValue().titleProperty());
        presetCol.setCellValueFactory(c -> Bindings.createObjectBinding(c.getValue()::preset));
        progressCol.setCellValueFactory(c -> c.getValue().progressProperty().asObject());
        progressCol.setCellFactory(ProgressBarTableCell.forTableColumn());
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        detailCol.setCellValueFactory(c -> c.getValue().detailProperty());
        table.setItems(jobs);
        table.setRowFactory(t -> createRow());

        ensureBinary();
    }

    void shutdown() {
        queue.shutdown();
    }

    @FXML
    private void onAdd() {
        String text = urlField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        for (String url : text.split("\\s+")) {
            enqueue(new DownloadJob(url, presetBox.getValue(), thumbnailCheck.isSelected(),
                    playlistCheck.isSelected(), outputDir));
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
        versionLabel.setText("yt-dlp: updating…");
        background(() -> {
            binary.update();
            return binary.version();
        }, this::showVersion, e -> {
            showVersionFallback();
            error("Updating yt-dlp failed", e);
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

    private void ensureBinary() {
        if (!binary.isInstalled()) {
            versionLabel.setText("yt-dlp: downloading latest release…");
        }
        background(() -> {
            if (!binary.isInstalled()) {
                binary.install();
            }
            return binary.version();
        }, this::showVersion, e -> {
            versionLabel.setText("yt-dlp: not available");
            error("Could not install yt-dlp to " + binary.path(), e);
        });
    }

    private void showVersion(String version) {
        versionLabel.setText("yt-dlp " + version);
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
            new ProcessBuilder("xdg-open", dir.toString()).start();
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
