package ch.epfl.biop.command;

import com.sun.javafx.application.PlatformImpl;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>ArgoLight gui")
public class ArgoLightGui implements Command{

    public static void createGui(){
        String title = "Metrology with ArgoLight plugin";
        String host = "omero-poc.epfl.ch:4064";
        ObservableList<String> microscopesList = FXCollections.observableArrayList(
                "SP8UP2", "SP8UP1", "SP8INT1");


        // label and text field for OMERO credentials and host
        Label labHost = new Label(host);
        Label labUsername = new Label("Username");
        TextField tfUsername = new TextField();
        labUsername.setLabelFor(tfUsername);
        Label labPassword = new Label("Password");
        PasswordField tfPassword = new PasswordField();
        labPassword.setLabelFor(tfPassword);

        // label and textField to choose root folder in case of local retriever
        Label labRootFolder  = new Label("Root Folder");
        TextField tfRootFolder = new TextField();
        labRootFolder.setLabelFor(tfRootFolder);
        labRootFolder.setDisable(true);
        tfRootFolder.setDisable(true);

        // button to choose root folder
        Button bRootFolder = new Button("Choose folder");
        bRootFolder.setOnAction(e->{
            DirectoryChooser directoryChooser = new DirectoryChooser();
            // directoryChooser.setInitialDirectory();
            directoryChooser.setTitle("Choose the microscopes' root folder");
            File rootFolder = directoryChooser.showDialog(null);
            if(rootFolder != null)
                tfRootFolder.setText(rootFolder.getAbsolutePath());
        });
        bRootFolder.setDisable(true);


        Label labMicroscope = new Label("Microscope");
        ComboBox cbMicroscope = new ComboBox(microscopesList);

        final ToggleGroup senderChoice = new ToggleGroup();

        // Radio button to choose OMERO retriever
        RadioButton rbOmeroSender = new RadioButton("OMERO");
        rbOmeroSender.setToggleGroup(senderChoice);
        rbOmeroSender.setSelected(true);

        // Radio button to choose local retriever
        RadioButton rbLocalSender = new RadioButton("Local");
        rbLocalSender.setToggleGroup(senderChoice);
        rbLocalSender.setSelected(false);

        CheckBox chkSaveHeatMap = new CheckBox("Save heat maps");
        chkSaveHeatMap.setMinWidth(CheckBox.USE_PREF_SIZE);
        chkSaveHeatMap.setSelected(false);

        CheckBox chkAllImages = new CheckBox("Process again existing images");
        chkAllImages.setMinWidth(CheckBox.USE_PREF_SIZE);
        chkAllImages.setSelected(false);

        final ToggleGroup retrieverChoice = new ToggleGroup();
        // Radio button to choose OMERO retriever
        RadioButton rbOmeroRetriever = new RadioButton("OMERO");
        rbOmeroRetriever.setToggleGroup(retrieverChoice);
        rbOmeroRetriever.setSelected(true);
        rbOmeroRetriever.selectedProperty().addListener((v, o, n) -> {
            labRootFolder.setDisable(rbOmeroRetriever.isSelected());
            tfRootFolder.setDisable(rbOmeroRetriever.isSelected());
            bRootFolder.setDisable(rbOmeroRetriever.isSelected());
            tfUsername.setDisable(!rbOmeroRetriever.isSelected());
            tfPassword.setDisable(!rbOmeroRetriever.isSelected());
            labHost.setDisable(!rbOmeroRetriever.isSelected());
            labUsername.setDisable(!rbOmeroRetriever.isSelected());
            labPassword.setDisable(!rbOmeroRetriever.isSelected());
            rbOmeroSender.setDisable(!rbOmeroRetriever.isSelected());

        });

        // Radio button to choose local retriever
        RadioButton rbLocalRetriever = new RadioButton("Local");
        rbLocalRetriever.setToggleGroup(retrieverChoice);
        rbLocalRetriever.setSelected(false);
        rbLocalRetriever.selectedProperty().addListener((v, o, n) -> {
            tfUsername.setDisable(rbLocalRetriever.isSelected());
            tfPassword.setDisable(rbLocalRetriever.isSelected());
            labHost.setDisable(rbLocalRetriever.isSelected());
            labUsername.setDisable(rbLocalRetriever.isSelected());
            labPassword.setDisable(rbLocalRetriever.isSelected());
            bRootFolder.setDisable(!rbLocalRetriever.isSelected());
            tfRootFolder.setDisable(!rbLocalRetriever.isSelected());
            labRootFolder.setDisable(!rbLocalRetriever.isSelected());
            rbOmeroSender.setSelected(!rbLocalRetriever.isSelected());
            rbLocalSender.setSelected(rbLocalRetriever.isSelected());
            rbOmeroSender.setDisable(rbLocalRetriever.isSelected());
        });

        // button to choose root folder
        Button bSettings = new Button("GUI Settings");
        bSettings.setOnAction(e->{
            createSettingsPane();
        });

        ColumnConstraints colFourth = new ColumnConstraints();
        colFourth.setPercentWidth(0);

        // create omero retriever pane
        int omeroRow = 0;
        GridPane omeroPane = new GridPane();
        omeroPane.add(rbOmeroRetriever, 0, omeroRow++);
        omeroPane.add(labHost, 0, omeroRow++, 2, 1);
        omeroPane.add(labUsername, 0, omeroRow);
        omeroPane.add(tfUsername, 1, omeroRow++);
        omeroPane.add(labPassword, 0, omeroRow);
        omeroPane.add(tfPassword, 1, omeroRow);
        omeroPane.setHgap(5);
        omeroPane.setVgap(5);

        // create local retriever pane
        int localRow = 0;
        GridPane localPane = new GridPane();
        localPane.add(rbLocalRetriever, 0, localRow++);
        localPane.add(labRootFolder, 0, localRow);
        localPane.add(tfRootFolder, 1, localRow++);
        localPane.add(bRootFolder, 0, localRow, 2,1);
        localPane.setHgap(5);
        localPane.setVgap(5);

        // Create Retriever pane
        int retrieverRow = 0;
        GridPane retrieverPane = new GridPane();
        retrieverPane.add(omeroPane, 0, retrieverRow);
        retrieverPane.add(localPane, 1, retrieverRow);
        retrieverPane.setHgap(10);
        retrieverPane.setVgap(5);

        // Create Retriever pane
        int microscopeRow = 0;
        GridPane microscopePane = new GridPane();
        microscopePane.getColumnConstraints().addAll(colFourth, colFourth, colFourth, colFourth);
        microscopePane.add(labMicroscope, 1, microscopeRow);
        microscopePane.add(cbMicroscope, 2, microscopeRow);
        microscopePane.setHgap(5);
        microscopePane.setVgap(5);

        // Create saving pane
        GridPane savingPane = new GridPane();
        savingPane.getColumnConstraints().addAll(colFourth, colFourth, colFourth, colFourth);
        savingPane.add(rbOmeroSender, 1, localRow);
        savingPane.add(rbLocalSender, 2, localRow++);
        savingPane.add(chkSaveHeatMap, 0, localRow++);
        savingPane.add(chkAllImages, 0, localRow, 2,1);
        savingPane.setHgap(5);
        savingPane.setVgap(5);

        // create general pane
        int generalRow = 0;
        final double MAX_FONT_SIZE = 20.0; // define max font size you need

        GridPane generalPane = new GridPane();

        Label retrieverHeader = new Label("Get images from");
        retrieverHeader.setFont(new Font(MAX_FONT_SIZE)); // set to Label
        generalPane.add(retrieverHeader, 0, generalRow++, 2, 1);
        generalPane.add(retrieverPane, 0, generalRow++);
        generalPane.add(new Separator(), 0, generalRow++, 2, 1);


        Label microscopeHeader = new Label("Choose your microscope");
        microscopeHeader.setFont(new Font(MAX_FONT_SIZE)); // set to Label
        generalPane.add(microscopeHeader, 0, generalRow++, 2, 1);
        generalPane.add(microscopePane, 0, generalRow++);
        generalPane.add(new Separator(), 0, generalRow++, 4, 1);

        Label savingHeader = new Label("Where to save results");
        savingHeader.setFont(new Font(MAX_FONT_SIZE)); // set to Label
        generalPane.add(savingHeader, 0, generalRow++, 2, 1);
        generalPane.add(savingPane, 0, generalRow++);
        generalPane.add(new Separator(), 0, generalRow++, 4, 1);

        Label settingsHeader = new Label("Setup your project");
        settingsHeader.setFont(new Font(MAX_FONT_SIZE)); // set to Label
        generalPane.add(settingsHeader, 0, generalRow++, 2, 1);
        generalPane.add(bSettings, 0, generalRow);

        generalPane.setHgap(10);
        generalPane.setVgap(10);
        // build the dialog box
        if (!buildDialog(title, generalPane)){
            System.out.println("Press cancel");
        }
        System.out.println("Press OK!");

    }


    private static void createSettingsPane(){
        // label and text field for OMERO credentials and host
        Label labHost = new Label("OMERO host");
        TextField tfUsername = new TextField();
        labHost.setLabelFor(tfUsername);

        Label labPort = new Label("OMERO port");
        TextField tfPort = new TextField();
        labPort.setLabelFor(tfPort);

        Label labMicroscope = new Label("Microscopes");
        TextField tfMicroscope = new TextField();
        labMicroscope.setLabelFor(tfMicroscope);

        // button to choose root folder
        Button bChooseMicroscope = new Button("Open file");
        bChooseMicroscope.setOnAction(e->{
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Choose the microscopes' csv file");
            File rootFolder = fileChooser.showOpenDialog(null);

            if(rootFolder == null || !rootFolder.exists()){
                Label labError = new Label("The file you selected does not exist. Please check your path / file");
                buildErrorMessage("No files", labError);
            } else if(!rootFolder.getAbsolutePath().endsWith(".csv")){
                Label labError = new Label("The file you selected is not a .csv file. Please select a .csv file");
                buildErrorMessage("Wrong file type", labError);
            } else {
                List<String> microscopes = parseCSV(rootFolder);
                String microscopesList = String.join(",", microscopes);
                tfMicroscope.setText(microscopesList);
            }

        });

        int row = 0;
        GridPane settingsPane = new GridPane();
        settingsPane.add(labHost, 0, row);
        settingsPane.add(tfUsername, 1, row++, 2, 1);
        settingsPane.add(labPort, 0, row);
        settingsPane.add(tfPort, 1, row++);
        settingsPane.add(labMicroscope, 0, row);
        settingsPane.add(tfMicroscope, 1, row);
        settingsPane.add(bChooseMicroscope, 2, row);
        settingsPane.setHgap(5);
        settingsPane.setVgap(5);

        if (!buildDialog("Setup your default settings", settingsPane))
            return;

        System.out.println("Press OK!");
    }

    private static boolean buildDialog(String title, Node content){
        List<ButtonType> buttons = new ArrayList<>();
        buttons.add(ButtonType.OK);
        buttons.add(ButtonType.CANCEL);

        Dialog<ButtonType> dialog = new Dialog<>();
        if(title != null)
            dialog.setTitle(title);
        else
            dialog.setTitle("");

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(buttons);

        dialog.setResizable(true);
        dialog.initModality(Modality.APPLICATION_MODAL);

        return dialog.showAndWait().orElse(ButtonType.NO) == ButtonType.OK;
    }

    private static void buildErrorMessage(String title, Node content){
        Dialog<ButtonType> dialog = new Alert(Alert.AlertType.ERROR);
        if(title != null)
            dialog.setTitle(title);
        else
            dialog.setTitle("");

        dialog.getDialogPane().setContent(content);

        dialog.setResizable(false);
        dialog.initModality(Modality.APPLICATION_MODAL);

        dialog.show();
    }

    private static List<String> parseCSV(File file){
        List<String> items = new ArrayList<>();
        try {
            //parsing a CSV file into BufferedReader class constructor
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null){   //returns a Boolean value
                items.add(line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return items;
    }

    public static void main(String... args){
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
    }

    @Override
    public void run() {
        PlatformImpl.startup(()->{});
        Platform.runLater(()->{
            createGui();
        });
    }
}
