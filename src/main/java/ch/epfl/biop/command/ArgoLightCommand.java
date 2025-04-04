package ch.epfl.biop.command;

import ch.epfl.biop.processing.ArgoSlideLivePreview;
import ch.epfl.biop.processing.Processing;
import ch.epfl.biop.retrievers.LocalRetriever;
import ch.epfl.biop.retrievers.OMERORetriever;
import ch.epfl.biop.retrievers.Retriever;
import ch.epfl.biop.senders.LocalSender;
import ch.epfl.biop.senders.OMEROSender;
import ch.epfl.biop.senders.Sender;
import ch.epfl.biop.utils.IJLogger;
import fr.igred.omero.Client;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.ImageWrapper;
import ij.IJ;
import ij.ImagePlus;
import loci.formats.FormatException;
import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JRootPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ItemEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This plugin runs image analysis pipeline on ArgoLight slide, pattern B, to measure the quality of objectives over
 * time. Three metrics (field distortion, field uniformity and full width at half maximum) are computed on a grid of
 * rings. Images have to be located on an OMERO server but you have the possibility to save results either on OMERO
 * or locally.
 */
@Plugin(type = Command.class, menuPath = "Plugins>BIOP>ArgoLight analysis tool")
public class ArgoLightCommand implements Command {
    private String userHost;
    private String userPort;
    private List<String> omeroMicroscopes = Collections.emptyList();
    private List<String> omeroProjects = Collections.emptyList();
    private List<String> omeroDatasets = Collections.emptyList();
    private List<String> localMicroscopes = Collections.emptyList();
    private List<String> userArgoSlides;
    private String defaultArgoSlide;
    private Map<String, List<String>> argoSlidesParameters = Collections.emptyMap();
    private String userRootFolder;
    private String userSaveFolder;
    private double userSigma;
    private double userMedianRadius;
    private String userThresholdMethod;
    private double userParticleThresh;
    private double userRingRadius;
    private boolean isDefaultSigma;
    private boolean isDefaultMedianRadius;
    private boolean isDefaultThresholdMethod;
    private boolean isDefaultParticleThresh;
    private boolean isDefaultRingRadius;
    private boolean startsProcessing = false;

    private JDialog mainDialog;
    private JDialog settingsDialog;
    private JDialog processingDialog;
    private JDialog livePreviewDialog;

    final private String defaultHost = "localhost";
    final private String defaultPort = "4064";
    final private int defaultArgoSpacing = 1;
    final private int defaultArgoFoV = 10;
    final private int defaultArgoNRings = 1;
    final private double defaultSigma = 0.2;
    final private double defaultMedianRadius = 0.2;
    final private String defaultThresholdMethod = "Li";
    final private double defaultParticleThresh = 5;
    final private double defaultRingRadius = 1.25;
    final private int sigmaUpperBound = 10;
    final private int particleThresholdUpperBound = 30;
    final private int ringRadiusUpperBound = 5;
    final private static List<String> thresholdingMethods = Arrays.asList("Default", "Huang", "Intermodes",
            "IsoData",  "IJ_IsoData", "Li", "MaxEntropy", "Mean", "MinError(I)", "Minimum", "Moments", "Otsu", "Percentile",
            "RenyiEntropy", "Shanbhag" , "Triangle", "Yen");
    final private String hostKey = "OMERO Host";
    final private String portKey = "OMERO Port";
    final private String argoSlidesKey = "ArgoSlides";
    final private String saveFolderKey = "Saving folder";
    final private String rootFolderKey = "Root folder";
    final private int argoDefaultPos = 0;
    final private int argoSpacingPos = 1;
    final private int argoFoVPos = 2;
    final private int argoNRingsPos = 3;
    final private String sigmaKey = "Sigma";
    final private String medianKey = "Median radius";
    final private String segmentationKey = "Segmentation method";
    final private String threshParticlesKey = "Particle size threshold";
    final private String ringRadiusKey = "Analyzed ring radius";

    final private String folderName = "." + File.separator + "plugins" + File.separator + "BIOP";
    final private String fileName = "ArgoLight_default_params.csv";
    final private String processingFileName = "ArgoLight_default_processing_params.csv";
    final private String argoSlideFileName = "ArgoLight_default_argoslide_params.csv";

    final private Font stdFont = new Font("Calibri", Font.PLAIN, 17);
    final private Font titleFont = new Font("Calibri", Font.BOLD, 22);
    private enum CONNECTION_STATE{
        CONNECTED("Disconnect"),
        DISCONNECTED("Connect");

        private final String name;

        CONNECTION_STATE(String name) {
            this.name = name;
        }
        String getName() { return this.name; }
    }

    final private Client client = new Client();
    private CONNECTION_STATE connection_state = CONNECTION_STATE.DISCONNECTED;
    private ImagePlus imageForLivePreview = null;
    private double pixelSizeForLivePreview;


    /**
     * Handle OMERO connection, run processing on all images and send results back to the initial location
     *
     * @param isOmeroRetriever
     * @param rootFolderPath
     * @param microscope
     * @param argoSlide
     * @param isOmeroSender
     * @param savingFolderPath
     * @param saveHeatMaps
     * @param allImages
     * @param cleanTargetSelection
     */
    private void runProcessing(boolean isOmeroRetriever, String omeroFolderName, boolean isMicOnProject,
                               String rootFolderPath, String microscope, String argoSlide, boolean isOmeroSender, String savingFolderPath,
                               boolean saveHeatMaps, boolean allImages, boolean cleanTargetSelection){
        boolean finalPopupMessage = true;
        if(!isOmeroRetriever && !new File(rootFolderPath).exists()){
            showWarningMessage("Root folder not accessible", "The root folder "+rootFolderPath+" does not exist");
            IJLogger.info("ArgoLight Analysis Tool exited");
            return;
        }
        if(!isOmeroSender && !new File(savingFolderPath).exists()){
            showWarningMessage("Saving folder not accessible", "The saving folder "+savingFolderPath+" does not exist");
            IJLogger.info("ArgoLight Analysis Tool exited");
            return;
        }
        if(argoSlide == null || argoSlide.isEmpty() || argoSlide.equalsIgnoreCase("null")){
            showWarningMessage("No ArgoSlide selected", "You need to create an ArgoSlide first. " +
                    "Click on 'General Settings' and fill 'ArgoSlides' field");
            IJLogger.info("ArgoLight Analysis Tool exited");
            return;
        }
        if(!argoSlidesParameters.containsKey(argoSlide)){
            showWarningMessage("No ArgoSlide settings", "You need to create settings for '"+argoSlide+"' ArgoSlide. " +
                    "Click on 'Settings' under 'Choose your ArgoSlide' and fill the fields");
            IJLogger.info("ArgoLight Analysis Tool exited");
            return;
        }

        try {
            // get the correct retriever
            Retriever retriever;
            String rawTarget;

            if(isOmeroRetriever) {
                // connect to OMERO
                if(!this.client.isConnected()) {
                    IJLogger.error("Not connected to OMERO");
                    IJLogger.info("ArgoLight Analysis Tool exited");
                    return;
                }

                retriever = new OMERORetriever(this.client, isMicOnProject);
                rawTarget = omeroFolderName;
            }
            else {
                retriever = new LocalRetriever(savingFolderPath);
                rawTarget = rootFolderPath;
            }

            // load images to process & set the cleaning
            boolean imageLoaded = retriever.loadImages(rawTarget, microscope, allImages, argoSlide);
            if(imageLoaded) {
                int nImages = retriever.getNImages();
                boolean cleanTarget = allImages && cleanTargetSelection;

                // create dedicated senders
                Sender sender;
                if (isOmeroSender) {
                    sender = new OMEROSender(this.client, retriever.getMicroscopeTarget(), cleanTarget);
                } else {
                    File savingFolder = new File(savingFolderPath);
                    sender = new LocalSender(savingFolder, microscope, cleanTarget, isOmeroRetriever);
                }

                // get the current argoSlide parameters
                List<String> argoParams = argoSlidesParameters.get(argoSlide);

                // run analysis
                if (nImages > 0)
                    Processing.run(retriever, saveHeatMaps, sender,
                            isDefaultSigma ? defaultSigma : userSigma,
                            isDefaultMedianRadius ? defaultMedianRadius : userMedianRadius,
                            isDefaultThresholdMethod ? defaultThresholdMethod : userThresholdMethod,
                            isDefaultParticleThresh ? defaultParticleThresh : userParticleThresh,
                            isDefaultRingRadius ? defaultRingRadius : userRingRadius,
                            argoSlide,
                            Integer.parseInt(argoParams.get(argoSpacingPos)),
                            Integer.parseInt(argoParams.get(argoFoVPos)),
                            Integer.parseInt(argoParams.get(argoNRingsPos)));
                else {
                    IJLogger.warn("Parent container : "+rawTarget + ", microscope " + microscope + " does not contain any images");
                    showWarningMessage("No Images", "<html> Parent container : "+rawTarget + ", microscope '" + microscope + "', does not contain any images." +
                            "<p>" +
                            "<ul>" +
                            "<li> The selected slide '"+argoSlide+"' may not matched the one used on the images</li><p>" +
                            "<li> The name of the images doesn't contain the name of the selected microscope '"+microscope+"'</li><p>" +
                            "<li> The images have already been processed</li>" +
                            "</ul>");
                    finalPopupMessage = false;
                }
            }else{
                finalPopupMessage = false;
                IJLogger.error("Images cannot be loaded from the parent container : "+rawTarget+", microscope "+microscope);
            }
        } catch (Exception e){
            finalPopupMessage = false;
            IJLogger.error("Unexpected issue occurred", e);
        } finally {
            if(this.client.isConnected()) {
                this.client.disconnect();
                IJLogger.info("Disconnected from OMERO ");
            }
        }
        IJLogger.info("ArgoLight Analysis Tool exited");

        if(finalPopupMessage) {
            showInfoMessage("Processing Done", "All images have been analyzed and results saved");
        }
    }

    private boolean connectToOmero(Client client, String username, char[] password){
        try {
            client.connect(userHost, Integer.parseInt(userPort), username, password);
            IJLogger.info("Successful connection to OMERO");
            return true;
        } catch (ServiceException e) {
            IJLogger.error("Cannot connect to OMERO", e);
            showErrorMessage("OMERO connections", "OMERO connection fails. Please check host, port and credentials");
            return false;
        }
    }


    /**
     * build the main user interface
     */
    private void createGui(){
        String title = "Metrology with ArgoLight plugin";
        JDialog generalPane = new JDialog();

        JPanel omeroPane = new JPanel();

        setDefaultGeneralParams();
        setDefaultArgoParams();
        setDefaultProcessingParams();

        // label host and port for OMERO retriever
        JLabel labHost = new JLabel (userHost +":"+ userPort);
        labHost.setFont(stdFont);

        // OMERO credentials for OMERO retriever
        JLabel  labUsername = new JLabel ("Username");
        labUsername.setFont(stdFont);
        JTextField tfUsername = new JTextField();
        tfUsername.setFont(stdFont);
        tfUsername.setColumns(15);

        JLabel labPassword = new JLabel("Password");
        labPassword.setFont(stdFont);
        JPasswordField tfPassword = new JPasswordField();
        tfPassword.setFont(stdFont);
        tfPassword.setColumns(15);

        // OMERO parent project for OMERO retriever
        JLabel labProject = new JLabel("Project");
        labProject.setFont(stdFont);

        DefaultComboBoxModel<String> modelCmbProject = new DefaultComboBoxModel<>();
        JComboBox<String> cbProject = new JComboBox<>(modelCmbProject);
        cbProject.setFont(stdFont);
        omeroProjects.forEach(cbProject::addItem);
        cbProject.setEnabled(false);

        // OMERO parent project for OMERO retriever
        JLabel labDataset = new JLabel("Dataset");
        labDataset.setFont(stdFont);
        labDataset.setVisible(false);

        DefaultComboBoxModel<String> modelCmbDataset = new DefaultComboBoxModel<>();
        JComboBox<String> cbDataset = new JComboBox<>(modelCmbDataset);
        cbDataset.setFont(stdFont);
        omeroDatasets.forEach(cbDataset::addItem);
        cbDataset.setVisible(false);

        JLabel labMicroscopeFolder = new JLabel("Microscopes folder on");
        labMicroscopeFolder.setFont(stdFont);

        connection_state = CONNECTION_STATE.DISCONNECTED;
        JButton bConnectToOmero = new JButton(connection_state.getName());
        bConnectToOmero.setFont(stdFont);

        // button to do live
        JButton bLivePreview = new JButton("Live preview");
        bLivePreview.setFont(stdFont);
        bLivePreview.setEnabled(false);

        JButton bOk = new JButton("OK");
        bOk.setFont(stdFont);

        // Root folder selection for local retriever
        JLabel labRootFolder  = new JLabel("Root Folder");
        labRootFolder.setFont(stdFont);
        JTextField tfRootFolder = new JTextField(userRootFolder);
        tfRootFolder.setFont(stdFont);
        tfRootFolder.setColumns(15);

        labRootFolder.setEnabled(false);
        tfRootFolder.setEnabled(false);

        // Microscope choice
        JLabel labMicroscope = new JLabel("Microscope");
        labMicroscope.setFont(stdFont);
        DefaultComboBoxModel<String> modelCmbMicroscope = new DefaultComboBoxModel<>();
        JComboBox<String> cbMicroscope = new JComboBox<>(modelCmbMicroscope);
        cbMicroscope.setFont(stdFont);
        omeroMicroscopes.forEach(cbMicroscope::addItem);
        cbMicroscope.setEnabled(false);

        cbProject.addItemListener(e->{
            if(e.getStateChange() == ItemEvent.SELECTED) {
                omeroMicroscopes = OMERORetriever.listDatasets(this.client, (String) cbProject.getSelectedItem());
                cbMicroscope.removeAllItems();
                omeroMicroscopes.forEach(cbMicroscope::addItem);
                cbMicroscope.setSelectedItem(omeroProjects);
            }
        });

        // button to choose root folder
        JButton bRootFolder = new JButton("Choose folder");
        bRootFolder.setFont(stdFont);
        bRootFolder.addActionListener(e->{
            JFileChooser directoryChooser = new JFileChooser();
            directoryChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if(new File(userRootFolder).exists())
                directoryChooser.setCurrentDirectory(new File(userRootFolder));
            directoryChooser.setDialogTitle("Choose the microscopes' root folder");
            directoryChooser.showDialog(generalPane,"Select");

            if (directoryChooser.getSelectedFile() != null){
                File selectedRootFolder = directoryChooser.getSelectedFile();
                tfRootFolder.setText(selectedRootFolder.getAbsolutePath());
                userRootFolder = selectedRootFolder.getAbsolutePath();
                localMicroscopes = LocalRetriever.listMicroscopes(selectedRootFolder);
                cbMicroscope.removeAllItems();
                localMicroscopes.forEach(cbMicroscope::addItem);
                cbMicroscope.setSelectedItem(localMicroscopes);
            }
        });

        bRootFolder.setEnabled(false);

        // Argoslide choice
        JLabel labArgoSlide = new JLabel("ArgoSlide");
        labArgoSlide.setFont(stdFont);
        DefaultComboBoxModel<String> modelCmbArgoSlide = new DefaultComboBoxModel<>();
        JComboBox<String> cbArgoSlide = new JComboBox<>(modelCmbArgoSlide);
        cbArgoSlide.setFont(stdFont);
        userArgoSlides.forEach(cbArgoSlide::addItem);
        if(defaultArgoSlide != null && !defaultArgoSlide.isEmpty())
            cbArgoSlide.setSelectedItem(defaultArgoSlide);
        cbArgoSlide.setEnabled(false);

        // Root folder selection for local sender
        JLabel labSavingFolder  = new JLabel("Saving Folder");
        labSavingFolder.setFont(stdFont);
        JTextField tfSavingFolder = new JTextField(userSaveFolder);
        tfSavingFolder.setFont(stdFont);
        tfSavingFolder.setColumns(15);

        labSavingFolder.setEnabled(false);
        tfSavingFolder.setEnabled(false);

        // button to choose root folder
        JButton bSavingFolder = new JButton("Choose folder");
        bSavingFolder.setFont(stdFont);
        bSavingFolder.addActionListener(e->{
            JFileChooser directoryChooser = new JFileChooser();
            directoryChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            directoryChooser.setDialogTitle("Choose the microscopes' saving folder");
            directoryChooser.showDialog(generalPane,"Select");

            if (directoryChooser.getSelectedFile() != null)
                tfSavingFolder.setText(directoryChooser.getSelectedFile().getAbsolutePath());

        });
        bSavingFolder.setEnabled(false);

        // checkbox to save heatmaps
        JCheckBox chkSaveHeatMap = new JCheckBox("Save heat maps");
        chkSaveHeatMap.setSelected(false);
        chkSaveHeatMap.setFont(stdFont);
        chkSaveHeatMap.setEnabled(false);

        // checkbox to remove previous data
        JCheckBox chkRemovePreviousRun = new JCheckBox("Remove previous runs");
        chkRemovePreviousRun.setSelected(false);
        chkRemovePreviousRun.setFont(stdFont);
        chkRemovePreviousRun.setEnabled(false);

        // checkbox to process again all images within the dataset/folder
        JCheckBox chkAllImages = new JCheckBox("Process again existing images");
        chkAllImages.setSelected(false);
        chkAllImages.setFont(stdFont);
        chkAllImages.addActionListener(e->{
            chkRemovePreviousRun.setEnabled(chkAllImages.isSelected());
        });
        chkAllImages.setEnabled(false);


        // Radio button to choose local retriever
        ButtonGroup omeroMicroscopeFolderChoice = new ButtonGroup();
        JRadioButton rbOmeroDataset = new JRadioButton("Dataset");
        rbOmeroDataset.setFont(stdFont);
        rbOmeroDataset.setSelected(true);
        rbOmeroDataset.setEnabled(false);
        omeroMicroscopeFolderChoice.add(rbOmeroDataset);
        rbOmeroDataset.addActionListener(e -> {
            cbProject.setVisible(rbOmeroDataset.isSelected());
            labProject.setVisible(rbOmeroDataset.isSelected());
            labDataset.setVisible(!rbOmeroDataset.isSelected());
            cbDataset.setVisible(!rbOmeroDataset.isSelected());

            cbProject.setSelectedItem(cbMicroscope.getSelectedItem());

            omeroMicroscopes = new ArrayList<>(omeroDatasets);
            cbMicroscope.removeAllItems();
            omeroMicroscopes.forEach(cbMicroscope::addItem);
            cbMicroscope.setSelectedItem(omeroMicroscopes);
        });

        JRadioButton rbOmeroProject = new JRadioButton("Project");
        rbOmeroProject.setFont(stdFont);
        rbOmeroProject.setSelected(false);
        rbOmeroProject.setEnabled(false);
        omeroMicroscopeFolderChoice.add(rbOmeroProject);
        rbOmeroProject.addActionListener(e -> {
            cbProject.setVisible(!rbOmeroProject.isSelected());
            labProject.setVisible(!rbOmeroProject.isSelected());
            labDataset.setVisible(rbOmeroProject.isSelected());
            cbDataset.setVisible(rbOmeroProject.isSelected());

            omeroDatasets = new ArrayList<>(omeroMicroscopes);
            omeroMicroscopes = new ArrayList<>(omeroProjects);

            cbMicroscope.removeAllItems();
            omeroMicroscopes.forEach(cbMicroscope::addItem);
            cbMicroscope.setSelectedItem(cbProject.getSelectedItem());

            cbDataset.removeAllItems();
            omeroDatasets.forEach(cbDataset::addItem);
            cbDataset.setSelectedItem(omeroDatasets);
        });

        // Radio button to choose local retriever
        ButtonGroup senderChoice = new ButtonGroup();
        JRadioButton rbLocalSender = new JRadioButton("Local");
        rbLocalSender.setFont(stdFont);
        rbLocalSender.setSelected(false);
        rbLocalSender.setEnabled(false);
        senderChoice.add(rbLocalSender);
        rbLocalSender.addActionListener(e -> {
            tfSavingFolder.setEnabled(rbLocalSender.isSelected());
            labSavingFolder.setEnabled(rbLocalSender.isSelected());
            bSavingFolder.setEnabled(rbLocalSender.isSelected());
        });

        // Radio button to choose OMERO retriever
        JRadioButton rbOmeroSender = new JRadioButton("OMERO");
        rbOmeroSender.setAlignmentX(JPanel.RIGHT_ALIGNMENT);
        rbOmeroSender.setFont(stdFont);
        rbOmeroSender.setSelected(true);
        rbOmeroSender.setEnabled(false);
        senderChoice.add(rbOmeroSender);
        rbOmeroSender.addActionListener(e -> {
            tfSavingFolder.setEnabled(!rbOmeroSender.isSelected());
            labSavingFolder.setEnabled(!rbOmeroSender.isSelected());
            bSavingFolder.setEnabled(!rbOmeroSender.isSelected());
        });

        // button to configure argoslide settings
        JButton bArgoSlideSettings = new JButton("Settings");
        bArgoSlideSettings.setFont(stdFont);
        bArgoSlideSettings.addActionListener(e->{
            createArgoSettingsPane(String.valueOf(cbArgoSlide.getSelectedItem()));
        });
        bArgoSlideSettings.setEnabled(false);

        // Radio button to choose OMERO retriever
        ButtonGroup retrieverChoice = new ButtonGroup();
        JRadioButton rbOmeroRetriever = new JRadioButton("OMERO");
        rbOmeroRetriever.setFont(stdFont);
        retrieverChoice.add(rbOmeroRetriever);
        rbOmeroRetriever.setSelected(true);
        rbOmeroRetriever.addActionListener(e -> {
            labRootFolder.setEnabled(!rbOmeroRetriever.isSelected());
            tfRootFolder.setEnabled(!rbOmeroRetriever.isSelected());
            bRootFolder.setEnabled(!rbOmeroRetriever.isSelected());
            tfUsername.setEnabled(rbOmeroRetriever.isSelected() && !this.client.isConnected());
            tfPassword.setEnabled(rbOmeroRetriever.isSelected() && !this.client.isConnected());
            labHost.setEnabled(rbOmeroRetriever.isSelected());
            labUsername.setEnabled(rbOmeroRetriever.isSelected());
            labPassword.setEnabled(rbOmeroRetriever.isSelected());
            rbOmeroSender.setEnabled(rbOmeroRetriever.isSelected());
            labProject.setEnabled(rbOmeroRetriever.isSelected());
            cbProject.setEnabled(!rbOmeroRetriever.isSelected() || this.client.isConnected());
            tfSavingFolder.setEnabled(!rbOmeroRetriever.isSelected());
            labSavingFolder.setEnabled(!rbOmeroRetriever.isSelected());
            bSavingFolder.setEnabled(!rbOmeroRetriever.isSelected());
            rbOmeroSender.setSelected(rbOmeroRetriever.isSelected());
            rbLocalSender.setSelected(!rbOmeroRetriever.isSelected());
            bConnectToOmero.setEnabled(rbOmeroRetriever.isSelected() && !this.client.isConnected());
            cbMicroscope.setEnabled(!rbOmeroRetriever.isSelected() || this.client.isConnected());
            rbOmeroProject.setEnabled(!rbOmeroRetriever.isSelected() || this.client.isConnected());
            rbOmeroDataset.setEnabled(!rbOmeroRetriever.isSelected() || this.client.isConnected());
            cbArgoSlide.setEnabled(!rbOmeroRetriever.isSelected() || this.client.isConnected());
            bArgoSlideSettings.setEnabled(!rbOmeroRetriever.isSelected() || this.client.isConnected());
            chkSaveHeatMap.setEnabled(!rbOmeroRetriever.isSelected() || this.client.isConnected());
            chkAllImages.setEnabled(!rbOmeroRetriever.isSelected() || this.client.isConnected());
            rbOmeroSender.setEnabled(!rbOmeroRetriever.isSelected() || this.client.isConnected());
            rbLocalSender.setEnabled(!rbOmeroRetriever.isSelected() || this.client.isConnected());
            bLivePreview.setEnabled(!rbOmeroRetriever.isSelected() || this.client.isConnected());
            cbDataset.setVisible(rbOmeroRetriever.isSelected() && rbOmeroProject.isSelected());
            labDataset.setVisible(rbOmeroRetriever.isSelected() && rbOmeroProject.isSelected());
            cbMicroscope.removeAllItems();
            omeroMicroscopes.forEach(cbMicroscope::addItem);
            cbMicroscope.setSelectedItem(omeroMicroscopes);

            if(rbOmeroRetriever.isSelected() && !this.client.isConnected())
                omeroPane.getRootPane().setDefaultButton(bConnectToOmero);
            else omeroPane.getRootPane().setDefaultButton(bOk);
        });

        // Radio button to choose local retriever
        JRadioButton rbLocalRetriever = new JRadioButton("Local");
        retrieverChoice.add(rbLocalRetriever);
        rbLocalRetriever.setFont(stdFont);
        rbLocalRetriever.setSelected(false);
        rbLocalRetriever.addActionListener(e -> {
            tfUsername.setEnabled(!rbLocalRetriever.isSelected());
            tfPassword.setEnabled(!rbLocalRetriever.isSelected());
            labHost.setEnabled(!rbLocalRetriever.isSelected());
            labUsername.setEnabled(!rbLocalRetriever.isSelected());
            labPassword.setEnabled(!rbLocalRetriever.isSelected());
            rbOmeroDataset.setEnabled(!rbLocalRetriever.isSelected());
            rbOmeroProject.setEnabled(!rbLocalRetriever.isSelected());
            labProject.setEnabled(!rbLocalRetriever.isSelected());
            cbProject.setEnabled(!rbLocalRetriever.isSelected());
            bRootFolder.setEnabled(rbLocalRetriever.isSelected());
            tfRootFolder.setEnabled(rbLocalRetriever.isSelected());
            labRootFolder.setEnabled(rbLocalRetriever.isSelected());
            rbOmeroSender.setSelected(!rbLocalRetriever.isSelected());
            rbOmeroSender.setEnabled(!rbLocalRetriever.isSelected());
            rbLocalSender.setSelected(rbLocalRetriever.isSelected());
            tfSavingFolder.setEnabled(rbLocalRetriever.isSelected());
            labSavingFolder.setEnabled(rbLocalRetriever.isSelected());
            bSavingFolder.setEnabled(rbLocalRetriever.isSelected());
            bConnectToOmero.setEnabled(!rbLocalRetriever.isSelected());
            cbMicroscope.setEnabled(rbLocalRetriever.isSelected());
            cbArgoSlide.setEnabled(rbLocalRetriever.isSelected());
            bArgoSlideSettings.setEnabled(rbLocalRetriever.isSelected());
            chkSaveHeatMap.setEnabled(rbLocalRetriever.isSelected());
            chkAllImages.setEnabled(rbLocalRetriever.isSelected());
            rbLocalSender.setEnabled(rbLocalRetriever.isSelected());
            bLivePreview.setEnabled(rbLocalRetriever.isSelected());
            cbDataset.setVisible(!rbLocalRetriever.isSelected());
            labDataset.setVisible(!rbLocalRetriever.isSelected());
            localMicroscopes = LocalRetriever.listMicroscopes(new File(tfRootFolder.getText()));
            cbMicroscope.removeAllItems();
            localMicroscopes.forEach(cbMicroscope::addItem);
            cbMicroscope.setSelectedItem(localMicroscopes);

            omeroPane.getRootPane().setDefaultButton(bOk);
        });

        cbMicroscope.addItemListener(e->{
            if(rbOmeroProject.isSelected() && rbOmeroRetriever.isSelected()){
                omeroDatasets = OMERORetriever.listDatasets(this.client, (String)cbMicroscope.getSelectedItem());
                cbDataset.removeAllItems();
                omeroDatasets.forEach(cbDataset::addItem);
                cbDataset.setSelectedItem(omeroDatasets);
            }
        });

        // button to configure general settings
        JButton bGeneralSettings = new JButton("General Settings");
        bGeneralSettings.setFont(stdFont);
        bGeneralSettings.addActionListener(e->{
            createSettingsPane();
            setDefaultGeneralParams();
            labHost.setText(userHost +":"+ userPort);
            cbArgoSlide.removeAllItems();
            userArgoSlides.forEach(cbArgoSlide::addItem);
            cbArgoSlide.setSelectedItem(userArgoSlides);
            tfRootFolder.setText(userRootFolder);
            tfSavingFolder.setText(userSaveFolder);
        });

        // button to configure processing settings
        JButton bProcessingSettings = new JButton("Processing Settings");
        bProcessingSettings.setFont(stdFont);
        bProcessingSettings.addActionListener(e->{
            createProcessingSettingsPane();
        });

        bLivePreview.addActionListener(e->{
            boolean isOmeroImage = false;
            if(rbOmeroRetriever.isSelected()) {
                isOmeroImage = true;
                if (!this.client.isConnected())
                    if (!connectToOmero(this.client, tfUsername.getText(), tfPassword.getPassword()))
                        return;
            }

            createLiveSettingsPane(isOmeroImage, (String)cbArgoSlide.getSelectedItem());
        });

        // OMERO connection action
        bConnectToOmero.addActionListener(e->{
            boolean enableButton = true;

            if(connection_state.equals(CONNECTION_STATE.DISCONNECTED)) {
                if (!this.client.isConnected())
                    if (connectToOmero(this.client, tfUsername.getText(), tfPassword.getPassword())) {
                        tfPassword.setText("");
                        omeroMicroscopes = OMERORetriever.listDatasets(this.client, (String) cbProject.getSelectedItem());
                        omeroProjects = OMERORetriever.listProjects(this.client);
                        connection_state = CONNECTION_STATE.CONNECTED;

                        // change the default button (when pressing enter with the keyboard)
                        omeroPane.getRootPane().setDefaultButton(bOk);
                        cbProject.requestFocus();
                    }
            }else{
                if (this.client.isConnected()) {
                    client.disconnect();
                    IJLogger.info("Disconnected from OMERO ");

                    enableButton = false;
                    connection_state = CONNECTION_STATE.DISCONNECTED;
                    omeroMicroscopes = Collections.emptyList();
                    omeroProjects = Collections.emptyList();
                    omeroPane.getRootPane().setDefaultButton(bConnectToOmero);
                    bConnectToOmero.requestFocus();
                }
            }

            cbMicroscope.setEnabled(enableButton);
            cbArgoSlide.setEnabled(enableButton);
            bArgoSlideSettings.setEnabled(enableButton);
            bLivePreview.setEnabled(enableButton);
            chkSaveHeatMap.setEnabled(enableButton);
            chkAllImages.setEnabled(enableButton);
            rbOmeroSender.setEnabled(enableButton);
            rbLocalSender.setEnabled(enableButton);
            tfUsername.setEnabled(!enableButton);
            tfPassword.setEnabled(!enableButton);
            cbProject.setEnabled(enableButton);
            rbOmeroDataset.setEnabled(enableButton);
            rbOmeroProject.setEnabled(enableButton);
            bConnectToOmero.setText(connection_state.getName());

            cbMicroscope.removeAllItems();
            omeroMicroscopes.forEach(cbMicroscope::addItem);
            cbMicroscope.setSelectedItem(omeroMicroscopes);

            cbProject.removeAllItems();
            omeroProjects.forEach(cbProject::addItem);
            cbProject.setSelectedItem(omeroProjects);
        });

        bOk.addActionListener(e->{
            startsProcessing = true;
            mainDialog.dispose();

            char[] password = tfPassword.getPassword();

            String folderName;
            if(rbOmeroProject.isSelected() && rbOmeroRetriever.isSelected())
                folderName = (String)cbDataset.getSelectedItem();
            else folderName = (String)cbProject.getSelectedItem();

            runProcessing(rbOmeroRetriever.isSelected(),
                    folderName,
                    rbOmeroProject.isSelected(),
                    tfRootFolder.getText(),
                    ((String)cbMicroscope.getSelectedItem()),
                    ((String)cbArgoSlide.getSelectedItem()),
                    rbOmeroSender.isSelected(),
                    tfSavingFolder.getText(),
                    chkSaveHeatMap.isSelected(),
                    chkAllImages.isSelected(),
                    chkRemovePreviousRun.isSelected());
        });

        JButton bCancel = new JButton("Cancel");
        bCancel.setFont(stdFont);
        bCancel.addActionListener(e->{
            mainDialog.dispose();
        });

        // build everything together
        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);

        int omeroRetrieverRow = 0;
        omeroPane.setLayout(new GridBagLayout());

        JLabel  retrieverTitle = new JLabel ("Get images from");
        retrieverTitle.setFont(titleFont);

        JPanel omeroFolderPane = new JPanel();
        constraints.gridx = 0;
        constraints.gridy = 0;
        omeroFolderPane.add(rbOmeroDataset);
        constraints.gridy = 1;
        omeroFolderPane.add(rbOmeroProject);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(retrieverTitle, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(rbOmeroRetriever, constraints);

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(rbLocalRetriever, constraints);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(labHost, constraints);

        constraints.gridwidth = 1; // set it back
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labUsername, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(tfUsername, constraints);

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labRootFolder, constraints);

        constraints.gridx = 3;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(tfRootFolder, constraints);

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labPassword, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(tfPassword, constraints);

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(bRootFolder, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(bConnectToOmero, constraints);

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labMicroscopeFolder, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(omeroFolderPane, constraints);

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labProject, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(cbProject, constraints);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(bLivePreview, constraints);

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        JLabel  microscopyTitle = new JLabel ("Choose your microscope");
        microscopyTitle.setFont(titleFont);

        constraints.gridwidth = 3; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(microscopyTitle, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labMicroscope, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(cbMicroscope, constraints);

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labDataset, constraints);

        constraints.gridx = 3;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(cbDataset, constraints);

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        JLabel  argoslideTitle = new JLabel ("Choose your Argoslide");
        argoslideTitle.setFont(titleFont);

        constraints.gridwidth = 3; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(argoslideTitle, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labArgoSlide, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(cbArgoSlide, constraints);

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(bArgoSlideSettings, constraints);

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        JLabel  senderTitle = new JLabel ("Where to save results");
        senderTitle.setFont(titleFont);

        constraints.gridwidth = 3; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(senderTitle, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(rbOmeroSender, constraints);

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(rbLocalSender, constraints);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(chkSaveHeatMap, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labSavingFolder, constraints);

        constraints.gridx = 3;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(tfSavingFolder, constraints);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(chkAllImages, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 2;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(bSavingFolder, constraints);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(chkRemovePreviousRun, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        JLabel settingsHeader = new JLabel("Setup your project");
        settingsHeader.setFont(titleFont);

        constraints.gridwidth = 3; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(settingsHeader, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(bGeneralSettings, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(bProcessingSettings, constraints);

        JPanel okCancelPane = new JPanel();
        constraints.gridx = 0;
        constraints.gridy = 0;
        okCancelPane.add(bOk);
        constraints.gridy = 1;
        okCancelPane.add(bCancel);

        constraints.gridx = 3;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(okCancelPane, constraints);

        mainDialog = new JDialog();
        mainDialog.setModal(true);
        mainDialog.setTitle(title);
        mainDialog.getContentPane().setLayout(new BorderLayout());
        omeroPane.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
        mainDialog.getContentPane().add(omeroPane);
        mainDialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        mainDialog.setResizable(false);

        if (JDialog.isDefaultLookAndFeelDecorated()) {
            boolean supportsWindowDecorations =
                    UIManager.getLookAndFeel().getSupportsWindowDecorations();
            if (supportsWindowDecorations) {
                mainDialog.setUndecorated(true);
                mainDialog.getRootPane().setWindowDecorationStyle(JRootPane.PLAIN_DIALOG);
            }
        }

        mainDialog.pack();
        mainDialog.setLocationRelativeTo(null);

        // initiate focus
        tfUsername.requestFocusInWindow();
        omeroPane.getRootPane().setDefaultButton(bConnectToOmero);

        mainDialog.setVisible(true);
        mainDialog.dispose();

        if(!startsProcessing) {
            if (this.client.isConnected()) {
                this.client.disconnect();
                IJLogger.info("Disconnected from OMERO ");
            }
            IJLogger.info("ArgoLight Analysis Tool exited");
        }
    }


    /**
     * Build the general settings user interface
     * @param argoSlide
     */
    private void createArgoSettingsPane(String argoSlide){

        // check if an argoSlide has been selected
        if(argoSlide == null || argoSlide.isEmpty() || argoSlide.equals("null")){
            showWarningMessage("No ArgoSlide selected", "You need to create an ArgoSlide first. " +
                    "Click on 'General Settings' and fill 'ArgoSlides' field");
            return;
        }

        // try to get the saved parameters. If the slide is not/wrongly referenced, set default parameters
        List<String> currentArgoslideParameters = argoSlidesParameters.getOrDefault(argoSlide, Collections.emptyList());
        if(currentArgoslideParameters.size() < 4){
            currentArgoslideParameters = new ArrayList<>(4);
            currentArgoslideParameters.add("false");
            currentArgoslideParameters.add(String.valueOf(defaultArgoSpacing));
            currentArgoslideParameters.add(String.valueOf(defaultArgoFoV));
            currentArgoslideParameters.add(String.valueOf(defaultArgoNRings));
        }

        // Distance between two horizontal rings
        JLabel labArgoSpacing = new JLabel("Distance between two horizontal rings (um)");
        labArgoSpacing.setFont(stdFont);
        SpinnerModel spModelArgoSpacing = new SpinnerNumberModel(Integer.parseInt(currentArgoslideParameters.get(argoSpacingPos)),1,1000,1);
        JSpinner spArgoSpacing = new JSpinner(spModelArgoSpacing);
        spArgoSpacing.setFont(stdFont);

        // Field of view
        JLabel labArgoFoV  = new JLabel("Field of view (um)");
        labArgoFoV.setFont(stdFont);
        SpinnerModel spModelArgoFoV = new SpinnerNumberModel(Integer.parseInt(currentArgoslideParameters.get(argoFoVPos)),10,10000,1);
        JSpinner spArgoFoV = new JSpinner(spModelArgoFoV);
        spArgoFoV.setFont(stdFont);

        // Number of rings along one line
        JLabel labArgoNRings = new JLabel("Number of rings along one line");
        labArgoNRings.setFont(stdFont);
        SpinnerModel spModelArgoNRings = new SpinnerNumberModel(Integer.parseInt(currentArgoslideParameters.get(argoNRingsPos)),1,10000,1);
        JSpinner spArgoNRings = new JSpinner(spModelArgoNRings);
        spArgoNRings.setFont(stdFont);

        // checkbox to set the default argoSlide
        JCheckBox chkDefaultArgoSlide = new JCheckBox("Set "+argoSlide+" as your default slide");
        chkDefaultArgoSlide.setSelected(currentArgoslideParameters.get(argoDefaultPos).equalsIgnoreCase("true"));
        chkDefaultArgoSlide.setFont(stdFont);

        // build everything together
        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);
        JPanel settingsPane = new JPanel();

        int settingsRow = 0;
        settingsPane.setLayout(new GridBagLayout());

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labArgoSpacing, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        settingsPane.add(spArgoSpacing, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labArgoFoV, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        settingsPane.add(spArgoFoV, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labArgoNRings, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        settingsPane.add(spArgoNRings, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(chkDefaultArgoSlide, constraints);

        JOptionPane pane = new JOptionPane(settingsPane, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                null, null);
        settingsDialog = pane.createDialog(mainDialog, "Setup "+argoSlide+" specifications");

        pane.selectInitialValue();
        settingsDialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        settingsDialog.setVisible(true);
        settingsDialog.dispose();

        Object selectedValue = pane.getValue();
        int opt = JOptionPane.CLOSED_OPTION;

        if(selectedValue instanceof Integer)
            opt = (Integer) selectedValue;

        if(opt == JOptionPane.OK_OPTION){
            // save a csv file with the user defined argoSlide parameters
            Map<String, List<String>> newMap = saveUserDefinedArgoSlideParams(
                    argoSlide,
                    (Integer) spArgoSpacing.getModel().getValue(),
                    (Integer) spArgoFoV.getModel().getValue(),
                    (Integer) spArgoNRings.getModel().getValue(),
                    chkDefaultArgoSlide.getModel().isSelected());

            // replace old values
            argoSlidesParameters.clear();
            argoSlidesParameters = newMap;
        }
    }


    /**
     * Build the general settings user interface
     */
    private void createSettingsPane(){
        JDialog generalPane = new JDialog();

        // OMERO host
        JLabel labHost = new JLabel("OMERO host");
        labHost.setFont(stdFont);
        JTextField tfHost = new JTextField(userHost);
        tfHost.setFont(stdFont);
        tfHost.setColumns(15);

        // OMERO port
        JLabel labPort = new JLabel("OMERO port");
        labPort.setFont(stdFont);
        JTextField tfPort = new JTextField(userPort);
        tfPort.setFont(stdFont);
        tfPort.setColumns(15);

        // list of available argoslides
        JLabel labArgoslide = new JLabel("Argoslides");
        labArgoslide.setFont(stdFont);
        JTextField tfArgoslide = new JTextField(String.join(",", userArgoSlides));
        tfArgoslide.setFont(stdFont);
        tfArgoslide.setColumns(15);

        // button to select a csv file containing all argoslides
        JButton bChooseArgoslide = new JButton("Open file");
        bChooseArgoslide.setFont(stdFont);
        bChooseArgoslide.addActionListener(e->{
            // define the file chooser
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fileChooser.setDialogTitle("Choose the argoslides' csv file");
            fileChooser.showDialog(generalPane,"Select");

            if (fileChooser.getSelectedFile() != null) {
                File rootFolder = fileChooser.getSelectedFile();
                if(!rootFolder.exists())
                    showErrorMessage("No files", "The file you selected does not exist. Please check your path / file");
                else if(!rootFolder.getAbsolutePath().endsWith(".csv"))
                    showErrorMessage("Wrong file type", "The file you selected is not a .csv file. Please select a .csv file");
                else {
                    List<String> argoslides = parseUserCSV(rootFolder);
                    String argoslidesList = String.join(",", argoslides);
                    tfArgoslide.setText(argoslidesList);
                }
            }
        });

        // Root folder for local retriever
        JLabel labRootFolder = new JLabel("Root folder");
        labRootFolder.setFont(stdFont);
        JTextField tfRootFolder = new JTextField(userRootFolder);
        tfRootFolder.setFont(stdFont);
        tfRootFolder.setColumns(15);

        // saving folder for local sender
        JLabel labSaveFolder = new JLabel("Saving folder");
        labSaveFolder.setFont(stdFont);
        JTextField tfSaveFolder = new JTextField(userSaveFolder);
        tfSaveFolder.setFont(stdFont);
        tfSaveFolder.setColumns(15);

        // button to choose root folder for local retriever
        JButton bChooseRootFolder = new JButton("Open folder");
        bChooseRootFolder.setFont(stdFont);
        bChooseRootFolder.addActionListener(e->{
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fileChooser.setDialogTitle("Choose the root folder where to get local image");
            fileChooser.showDialog(generalPane,"Select");

            if (fileChooser.getSelectedFile() != null)
                tfRootFolder.setText(fileChooser.getSelectedFile().getAbsolutePath());
        });

        // button to choose saving folder for local sender
        JButton bChooseSaveFolder = new JButton("Open folder");
        bChooseSaveFolder.setFont(stdFont);
        bChooseSaveFolder.addActionListener(e->{
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fileChooser.setDialogTitle("Choose the folder where to save results");
            fileChooser.showDialog(generalPane,"Select");

            if(fileChooser.getSelectedFile() != null)
                tfSaveFolder.setText(fileChooser.getSelectedFile().getAbsolutePath());
        });

        // build everything together
        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);
        JPanel settingsPane = new JPanel();

        int settingsRow = 0;
        settingsPane.setLayout(new GridBagLayout());

        JLabel  retrieverTitle = new JLabel ("Get images from");
        retrieverTitle.setFont(titleFont);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labHost, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        settingsPane.add(tfHost, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labPort, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        settingsPane.add(tfPort, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labArgoslide, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(tfArgoslide, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(bChooseArgoslide, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labRootFolder, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(tfRootFolder, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(bChooseRootFolder, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labSaveFolder, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(tfSaveFolder, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow;
        settingsPane.add(bChooseSaveFolder, constraints);

        JOptionPane pane = new JOptionPane(settingsPane, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                null, null);
        settingsDialog = pane.createDialog(mainDialog, "Setup your default settings");

        pane.selectInitialValue();
        settingsDialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        settingsDialog.setVisible(true);
        settingsDialog.dispose();

        Object selectedValue = pane.getValue();
        int opt = JOptionPane.CLOSED_OPTION;

        if(selectedValue instanceof Integer)
            opt = (Integer) selectedValue;

        if(opt == JOptionPane.OK_OPTION){
            String badEntries = "";

            // test the validity of all user entries
            try {
                Double.parseDouble(tfPort.getText());
                userPort = tfPort.getText();
            }catch (Exception e){
                badEntries += " - port";
            }

            userHost = tfHost.getText();
            userRootFolder = tfRootFolder.getText();
            userSaveFolder = tfSaveFolder.getText();

            // save a csv file with the user defined parameters
            saveUserDefinedGeneralParams(userHost,
                    userPort,
                    tfArgoslide.getText(),
                    userRootFolder,
                    userSaveFolder);

            if(!badEntries.isEmpty())
                showWarningMessage("Bad entries", "The following entries require numeric values : "+badEntries);
        }
    }


    /**
     * Build processing settings user interface
     */
    private void createProcessingSettingsPane(){

        // Sigma for gaussian blurring
        JLabel labSigma = new JLabel("Gaussian blur sigma (um)");
        labSigma.setFont(stdFont);
        SpinnerModel spModelSigma = new SpinnerNumberModel(userSigma,0.01,10,0.01);
        JSpinner spSigma = new JSpinner(spModelSigma);
        spSigma.setFont(stdFont);
        spSigma.setEnabled(!isDefaultSigma);

        // median radius for median filtering
        JLabel labMedian = new JLabel("Median filter radius (um)");
        labMedian.setFont(stdFont);
        SpinnerModel spModelMedian = new SpinnerNumberModel(userMedianRadius,0.01,10,0.01);
        JSpinner spMedian = new JSpinner(spModelMedian);
        spMedian.setFont(stdFont);
        spMedian.setEnabled(!isDefaultMedianRadius);

        // thresholding method
        JLabel labThreshSeg = new JLabel("Thresholding method for segmentation");
        labThreshSeg.setFont(stdFont);
        DefaultComboBoxModel<String> modelCmbSegmentation = new DefaultComboBoxModel<>();
        JComboBox<String> cbSegmentation = new JComboBox<>(modelCmbSegmentation);
        cbSegmentation.setFont(stdFont);
        thresholdingMethods.forEach(cbSegmentation::addItem);
        cbSegmentation.setSelectedItem(userThresholdMethod);
        cbSegmentation.setEnabled(!isDefaultThresholdMethod);

        // threshold on particle size
        JLabel labThreshParticles = new JLabel("Threshold on segmented particles (um^2)");
        labThreshParticles.setFont(stdFont);
        SpinnerModel spModelThreshParticles = new SpinnerNumberModel(userParticleThresh,0.001,20,0.001);
        JSpinner spThreshParticles = new JSpinner(spModelThreshParticles);
        spThreshParticles.setFont(stdFont);
        spThreshParticles.setEnabled(!isDefaultParticleThresh);

        // analyzed ring radius
        JLabel labRingRadius = new JLabel("Analyzed ring radius (um)");
        labRingRadius.setFont(stdFont);
        SpinnerModel spModelRingRadius = new SpinnerNumberModel(userRingRadius,0.01,3,0.01);
        JSpinner spRingRadius = new JSpinner(spModelRingRadius);
        spRingRadius.setFont(stdFont);
        spRingRadius.setEnabled(!isDefaultRingRadius);

        // checkbox to activate default parameters
        JCheckBox chkSigma = new JCheckBox("default");
        chkSigma.setSelected(isDefaultSigma);
        chkSigma.setFont(stdFont);
        chkSigma.addActionListener(e->{
            spSigma.setEnabled(!chkSigma.isSelected());
        });

        JCheckBox chkMedian = new JCheckBox("default");
        chkMedian.setSelected(isDefaultMedianRadius);
        chkMedian.setFont(stdFont);
        chkMedian.addActionListener(e->{
            spMedian.setEnabled(!chkMedian.isSelected());
        });

        JCheckBox chkThreshSeg = new JCheckBox("default");
        chkThreshSeg.setSelected(isDefaultThresholdMethod);
        chkThreshSeg.setFont(stdFont);
        chkThreshSeg.addActionListener(e->{
            cbSegmentation.setEnabled(!chkThreshSeg.isSelected());
        });

        JCheckBox chkThreshParticles = new JCheckBox("default");
        chkThreshParticles.setSelected(isDefaultParticleThresh);
        chkThreshParticles.setFont(stdFont);
        chkThreshParticles.addActionListener(e->{
            spThreshParticles.setEnabled(!chkThreshParticles.isSelected());
        });

        JCheckBox chkRingRadius = new JCheckBox("default");
        chkRingRadius.setSelected(isDefaultRingRadius);
        chkRingRadius.setFont(stdFont);
        chkRingRadius.addActionListener(e->{
            spRingRadius.setEnabled(!chkRingRadius.isSelected());
        });

        // checkbox to set the default argoSlide
        JCheckBox chkUseOnlyOnce = new JCheckBox("Use only once");
        chkUseOnlyOnce.setSelected(false);
        chkUseOnlyOnce.setFont(stdFont);

        // build everything together
        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);
        JPanel settingsPane = new JPanel();

        int settingsRow = 0;
        settingsPane.setLayout(new GridBagLayout());

        JLabel  retrieverTitle = new JLabel ("Get images from");
        retrieverTitle.setFont(titleFont);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labSigma, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(chkSigma, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(spSigma, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labMedian, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(chkMedian, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(spMedian, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labThreshSeg, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(chkThreshSeg, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(cbSegmentation, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labThreshParticles, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(chkThreshParticles, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(spThreshParticles, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labRingRadius, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(chkRingRadius, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(spRingRadius, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(chkUseOnlyOnce, constraints);

        JOptionPane pane = new JOptionPane(settingsPane, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                null, null);
        processingDialog = pane.createDialog(mainDialog, "Setup your default settings for processing");

        pane.selectInitialValue();
        processingDialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        processingDialog.setVisible(true);
        processingDialog.dispose();

        Object selectedValue = pane.getValue();
        int opt = JOptionPane.CLOSED_OPTION;

        if(selectedValue instanceof Integer)
            opt = (Integer) selectedValue;

        if(opt == JOptionPane.OK_OPTION){
            isDefaultSigma = chkSigma.isSelected();
            isDefaultMedianRadius = chkMedian.isSelected();
            isDefaultThresholdMethod = chkThreshSeg.isSelected();
            isDefaultParticleThresh = chkThreshParticles.isSelected();
            isDefaultRingRadius = chkRingRadius.isSelected();
            userSigma = (double)spSigma.getValue();
            userMedianRadius = (double)spMedian.getValue();
            userThresholdMethod = (String)cbSegmentation.getSelectedItem();
            userParticleThresh = (double)spThreshParticles.getValue();
            userRingRadius = (double)spRingRadius.getValue();

            if(!chkUseOnlyOnce.getModel().isSelected()) {
                saveUserDefinedProcessingParams(isDefaultSigma,
                        isDefaultMedianRadius,
                        isDefaultThresholdMethod,
                        isDefaultParticleThresh,
                        isDefaultRingRadius,
                        userSigma,
                        userMedianRadius,
                        userThresholdMethod,
                        userParticleThresh,
                        userRingRadius);
            }
        }
    }

    /**
     * Build the GUI for live settings
     * @param isOmeroImage
     * @param argoSlide
     */
    private void createLiveSettingsPane(boolean isOmeroImage, String argoSlide){

        // get the current Argoslide
        List<String> currentArgoslideParameters = argoSlidesParameters.getOrDefault(argoSlide, Collections.emptyList());
        if(currentArgoslideParameters.isEmpty()){
            showWarningMessage("No ArgoSlide selected","Please select an ArgoSlide first");
            return;
        }

        // get the current argoSlide parameters
        int currentArgoSpacing = Integer.parseInt(currentArgoslideParameters.get(argoSpacingPos));
        int currentArgoFoV = Integer.parseInt(currentArgoslideParameters.get(argoFoVPos));
        int currentArgoNRings = Integer.parseInt(currentArgoslideParameters.get(argoNRingsPos));

        JDialog generalPane = new JDialog();

        // OMERO image ID
        JLabel labOmeroImage = new JLabel("Image ID");
        labOmeroImage.setFont(stdFont);
        JTextField tfOmeroImage = new JTextField();
        tfOmeroImage.setFont(stdFont);
        tfOmeroImage.setColumns(7);

        // Image Path
        JLabel labImage = new JLabel("Image path");
        labImage.setFont(stdFont);
        JTextField tfImage = new JTextField();
        tfImage.setFont(stdFont);
        tfImage.setColumns(7);

        // Sigma for gaussian blurring
        JLabel labSigma = new JLabel("Gaussian blur sigma (um)");
        labSigma.setFont(stdFont);
        SpinnerModel spModelSigma = new SpinnerNumberModel(userSigma,0.01,10,0.01);
        JSpinner spSigma = new JSpinner(spModelSigma);
        spSigma.setFont(stdFont);
        spSigma.setEnabled(!isDefaultSigma);

        // median radius for median filtering
        JLabel labMedian = new JLabel("Median filter radius (um)");
        labMedian.setFont(stdFont);
        SpinnerModel spModelMedian = new SpinnerNumberModel(userMedianRadius,0.01,10,0.01);
        JSpinner spMedian = new JSpinner(spModelMedian);
        spMedian.setFont(stdFont);
        spMedian.setEnabled(!isDefaultMedianRadius);

        // thresholding method
        JLabel labThreshSeg = new JLabel("Thresholding method for segmentation");
        labThreshSeg.setFont(stdFont);
        DefaultComboBoxModel<String> modelCmbSegmentation = new DefaultComboBoxModel<>();
        JComboBox<String> cbSegmentation = new JComboBox<>(modelCmbSegmentation);
        cbSegmentation.setFont(stdFont);
        thresholdingMethods.forEach(cbSegmentation::addItem);
        cbSegmentation.setSelectedItem(userThresholdMethod);
        cbSegmentation.setEnabled(!isDefaultThresholdMethod);

        // threshold on particle size
        JLabel labThreshParticles = new JLabel("Threshold on segmented particles (um^2)");
        labThreshParticles.setFont(stdFont);
        SpinnerModel spModelThreshParticles = new SpinnerNumberModel(userParticleThresh,0.001,20,0.001);
        JSpinner spThreshParticles = new JSpinner(spModelThreshParticles);
        spThreshParticles.setFont(stdFont);
        spThreshParticles.setEnabled(!isDefaultParticleThresh);

        // analyzed ring radius
        JLabel labRingRadius = new JLabel("Analyzed ring radius (um)");
        labRingRadius.setFont(stdFont);
        SpinnerModel spModelRingRadius = new SpinnerNumberModel(userRingRadius,0.01,3,0.01);
        JSpinner spRingRadius = new JSpinner(spModelRingRadius);
        spRingRadius.setFont(stdFont);
        spRingRadius.setEnabled(!isDefaultRingRadius);

        JLabel labXAverageStepLeg = new JLabel("Average horizontal step (pix)");
        labXAverageStepLeg.setFont(stdFont);
        JLabel labXAverageStep = new JLabel("");
        labXAverageStep.setFont(stdFont);

        JLabel labYAverageStepLeg = new JLabel("Average vertical step (pix)");
        labYAverageStepLeg.setFont(stdFont);
        JLabel labYAverageStep = new JLabel("");
        labYAverageStep.setFont(stdFont);

        JLabel labRotationAngleLeg = new JLabel("Rotation angle (°)");
        labRotationAngleLeg.setFont(stdFont);
        JLabel labRotationAngle = new JLabel("");
        labRotationAngle.setFont(stdFont);

        JLabel labIdealGrid = new JLabel("Ideal grid");
        labIdealGrid.setFont(stdFont);
        labIdealGrid.setForeground(Color.GREEN);

        JLabel labDetectedGrid = new JLabel("Measured grid");
        labDetectedGrid.setFont(stdFont);
        labDetectedGrid.setForeground(Color.RED);

        // checkbox to activate default parameters
        JCheckBox chkSigma = new JCheckBox("default");
        chkSigma.setSelected(isDefaultSigma);
        chkSigma.setFont(stdFont);
        chkSigma.addActionListener(e->{
            spSigma.setEnabled(!chkSigma.isSelected());
        });

        JCheckBox chkMedian = new JCheckBox("default");
        chkMedian.setSelected(isDefaultMedianRadius);
        chkMedian.setFont(stdFont);
        chkMedian.addActionListener(e->{
            spMedian.setEnabled(!chkMedian.isSelected());
        });

        JCheckBox chkThreshSeg = new JCheckBox("default");
        chkThreshSeg.setSelected(isDefaultThresholdMethod);
        chkThreshSeg.setFont(stdFont);
        chkThreshSeg.addActionListener(e->{
            cbSegmentation.setEnabled(!chkThreshSeg.isSelected());
        });

        JCheckBox chkThreshParticles = new JCheckBox("default");
        chkThreshParticles.setSelected(isDefaultParticleThresh);
        chkThreshParticles.setFont(stdFont);
        chkThreshParticles.addActionListener(e->{
            spThreshParticles.setEnabled(!chkThreshParticles.isSelected());
        });

        JCheckBox chkRingRadius = new JCheckBox("default");
        chkRingRadius.setSelected(isDefaultRingRadius);
        chkRingRadius.setFont(stdFont);
        chkRingRadius.addActionListener(e->{
            spRingRadius.setEnabled(!chkRingRadius.isSelected());
        });

        spSigma.addChangeListener(e->{
            if(this.imageForLivePreview != null) {
                double sigmaPreview = (double) spSigma.getValue();
                double medianRadiusPreview = (double) spMedian.getValue();
                String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
                double particleThreshPreview = (double) spThreshParticles.getValue();
                double ringRadiusPreview = (double) spRingRadius.getValue();
                ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                        particleThreshPreview, ringRadiusPreview, currentArgoSpacing, currentArgoFoV);
                labXAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getXAvgStep()));
                labYAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getYAvgStep()));
                labRotationAngle.setText(String.valueOf(ArgoSlideLivePreview.getRotationAngle()));
            }
        });

        spMedian.addChangeListener(e->{
            if(this.imageForLivePreview != null) {
                double sigmaPreview = (double) spSigma.getValue();
                double medianRadiusPreview = (double) spMedian.getValue();
                String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
                double particleThreshPreview = (double) spThreshParticles.getValue();
                double ringRadiusPreview = (double) spRingRadius.getValue();
                ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                        particleThreshPreview, ringRadiusPreview, currentArgoSpacing, currentArgoFoV);
                labXAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getXAvgStep()));
                labYAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getYAvgStep()));
                labRotationAngle.setText(String.valueOf(ArgoSlideLivePreview.getRotationAngle()));
            }
        });

        spThreshParticles.addChangeListener(e->{
            if(this.imageForLivePreview != null) {
                double sigmaPreview = (double) spSigma.getValue();
                double medianRadiusPreview = (double) spMedian.getValue();
                String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
                double particleThreshPreview = (double) spThreshParticles.getValue();
                double ringRadiusPreview = (double) spRingRadius.getValue();
                ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                        particleThreshPreview, ringRadiusPreview, currentArgoSpacing, currentArgoFoV);
                labXAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getXAvgStep()));
                labYAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getYAvgStep()));
                labRotationAngle.setText(String.valueOf(ArgoSlideLivePreview.getRotationAngle()));
            }
        });

        spRingRadius.addChangeListener(e->{
            if(this.imageForLivePreview != null) {
                double sigmaPreview = (double) spSigma.getValue();
                double medianRadiusPreview = (double) spMedian.getValue();
                String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
                double particleThreshPreview = (double) spThreshParticles.getValue();
                double ringRadiusPreview = (double) spRingRadius.getValue();
                ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                        particleThreshPreview, ringRadiusPreview, currentArgoSpacing, currentArgoFoV);
                labXAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getXAvgStep()));
                labYAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getYAvgStep()));
                labRotationAngle.setText(String.valueOf(ArgoSlideLivePreview.getRotationAngle()));
            }
        });

        cbSegmentation.addItemListener(e->{
            if(this.imageForLivePreview != null) {
                double sigmaPreview = (double) spSigma.getValue();
                double medianRadiusPreview = (double) spMedian.getValue();
                String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
                double particleThreshPreview = (double) spThreshParticles.getValue();
                double ringRadiusPreview = (double) spRingRadius.getValue();
                ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                        particleThreshPreview, ringRadiusPreview, currentArgoSpacing, currentArgoFoV);
                labXAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getXAvgStep()));
                labYAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getYAvgStep()));
                labRotationAngle.setText(String.valueOf(ArgoSlideLivePreview.getRotationAngle()));
            }
        });

        // button to select an image for live preview
        JButton bChooseImageToTest = new JButton("Choose image");
        bChooseImageToTest.setFont(stdFont);
        bChooseImageToTest.addActionListener(e->{
            // define the file chooser
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fileChooser.setDialogTitle("Choose one image for test");
            fileChooser.showDialog(generalPane,"Select");

            if (fileChooser.getSelectedFile() != null) {
                File imageFile = fileChooser.getSelectedFile();
                tfImage.setText(imageFile.getAbsolutePath());
            }
        });

        // button to load the image into the simulation
        JButton bLoadImage = new JButton("Load");
        bLoadImage.setFont(stdFont);
        bLoadImage.addActionListener(e->{
            IJ.run("Close All", "");
            this.imageForLivePreview = null;
            if(isOmeroImage){
                String idString = tfOmeroImage.getText();
                try {
                    // read the image as ImagePlus
                    long imgId = Long.parseLong(idString);
                    ImageWrapper image = this.client.getImage(imgId);
                    ImagePlus imp = image.toImagePlus(this.client);

                    // extract the first channel
                    ImagePlus channel = IJ.createHyperStack(imp.getTitle(), imp.getWidth(), imp.getHeight(), 1, 1, 1, imp.getBitDepth());
                    imp.setPosition(1, 1, 1);
                    channel.setProcessor(imp.getProcessor());
                    this.imageForLivePreview = channel;
                    this.pixelSizeForLivePreview = imp.getCalibration().pixelWidth;

                } catch (Exception ex){
                    showErrorMessage("OMERO image", "Cannot read image "+idString+" from OMERO");
                }
            }else{
                String imgPath = tfImage.getText();
                File imgFile = new File(imgPath);
                if(imgFile.exists() && imgFile.isFile()) {
                    try {
                        // choose import options
                        ImporterOptions options = new ImporterOptions();
                        options.setId(imgFile.getAbsolutePath());
                        // disable all series
                        options.setOpenAllSeries(true);
                        options.setVirtual(true);

                        // open the image as ImagePlus
                        ImagePlus[] images = BF.openImagePlus(options);
                        if(images == null)
                            showErrorMessage("Local image", "Cannot read image "+imgPath);
                        else {
                            options.setVirtual(false);
                            options.setOpenAllSeries(false);
                            if(images.length > 1) {
                                int serie = createSelectSerieGui(images.length);
                                options.setSeriesOn(serie, true);
                            }else{
                                options.setSeriesOn(1, true);
                            }

                            // open the selected serie
                            images = BF.openImagePlus(options);
                            ImagePlus imp = images[0];

                            // extract the first channel
                            ImagePlus channel = IJ.createHyperStack(imp.getTitle(), imp.getWidth(), imp.getHeight(), 1, 1, 1, imp.getBitDepth());
                            imp.setPosition(1, 1, 1);
                            channel.setProcessor(imp.getProcessor());
                            this.imageForLivePreview = channel;
                            this.pixelSizeForLivePreview = imp.getCalibration().pixelWidth;
                        }

                    } catch (FormatException | IOException ex) {
                        showErrorMessage("Local image", "Cannot read image "+imgPath);
                    }
                }
                else
                    showErrorMessage("Local image", "Cannot read image "+imgPath);
            }

            if(this.imageForLivePreview != null){
                this.imageForLivePreview.show();
                int width = this.imageForLivePreview.getWindow().getWidth();
                Point loc = this.imageForLivePreview.getWindow().getLocationOnScreen();
                this.imageForLivePreview.getWindow().setLocation(IJ.getScreenSize().width-width,loc.y);
                this.imageForLivePreview.getWindow().setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);

                double sigmaPreview = (double) spSigma.getValue();
                double medianRadiusPreview = (double) spMedian.getValue();
                String thresholdMethodPreview = (String) cbSegmentation.getSelectedItem();
                double particleThreshPreview = (double) spThreshParticles.getValue();
                double ringRadiusPreview = (double) spRingRadius.getValue();
                ArgoSlideLivePreview.run(this.imageForLivePreview, this.pixelSizeForLivePreview, sigmaPreview, medianRadiusPreview, thresholdMethodPreview,
                        particleThreshPreview, ringRadiusPreview, currentArgoSpacing, currentArgoFoV);
                labXAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getXAvgStep()));
                labYAverageStep.setText(String.valueOf(ArgoSlideLivePreview.getYAvgStep()));
                labRotationAngle.setText(String.valueOf(ArgoSlideLivePreview.getRotationAngle()));
            }
        });

        // build everything together
        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);
        JPanel livePreviewPane = new JPanel();

        int settingsRow = 0;
        livePreviewPane.setLayout(new GridBagLayout());

        JLabel retrieverTitle = new JLabel ("Get images from");
        retrieverTitle.setFont(titleFont);

        if(isOmeroImage) {
            constraints.gridx = 0;
            constraints.gridy = settingsRow;
            livePreviewPane.add(labOmeroImage, constraints);

            constraints.gridx = 1;
            constraints.gridy = settingsRow;
            livePreviewPane.add(tfOmeroImage, constraints);
        }else{
            constraints.gridx = 0;
            constraints.gridy = settingsRow;
            livePreviewPane.add(labImage, constraints);

            constraints.gridx = 1;
            constraints.gridy = settingsRow;
            livePreviewPane.add(tfImage, constraints);

            constraints.gridx = 2;
            constraints.gridy = settingsRow;
            livePreviewPane.add(bChooseImageToTest, constraints);
        }
        settingsRow++;

        constraints.gridx = 0;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(bLoadImage, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labSigma, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        livePreviewPane.add(chkSigma, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(spSigma, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labMedian, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        livePreviewPane.add(chkMedian, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(spMedian, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labThreshSeg, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        livePreviewPane.add(chkThreshSeg, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(cbSegmentation, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labThreshParticles, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        livePreviewPane.add(chkThreshParticles, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(spThreshParticles, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labRingRadius, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        livePreviewPane.add(chkRingRadius, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(spRingRadius, constraints);

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labXAverageStepLeg, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(labXAverageStep, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labYAverageStepLeg, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(labYAverageStep, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labRotationAngleLeg, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(labRotationAngle, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow++;
        livePreviewPane.add(labDetectedGrid, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        livePreviewPane.add(labIdealGrid, constraints);

        JOptionPane pane = new JOptionPane(livePreviewPane, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                null, null);
        livePreviewDialog = pane.createDialog(mainDialog, "Live preview");

        pane.selectInitialValue();
        livePreviewDialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        livePreviewDialog.setVisible(true);
        livePreviewDialog.dispose();

        Object selectedValue = pane.getValue();
        int opt = JOptionPane.CLOSED_OPTION;

        if(selectedValue instanceof Integer)
            opt = (Integer) selectedValue;

        if(opt == JOptionPane.OK_OPTION) {
            opt = showConfirmMessage("Confirm", "<html><h2>Do you want to use these settings as default ones ?</h2>" +
                    "<p>" +
                    "<ul>" +
                    "<li> YES : Previous default settings will be overwritten by live settings.</li><p>" +
                    "<li> NO : Live settings are only used in the current simulation but the default settings are kept unmodified.</li><p>" +
                    "<li> CANCEL : Live settings are discarded.</li>" +
                    "</ul>");
            if(opt == JOptionPane.YES_OPTION || opt == JOptionPane.NO_OPTION) {
                isDefaultSigma = chkSigma.isSelected();
                isDefaultMedianRadius = chkMedian.isSelected();
                isDefaultThresholdMethod = chkThreshSeg.isSelected();
                isDefaultParticleThresh = chkThreshParticles.isSelected();
                isDefaultRingRadius = chkRingRadius.isSelected();
                userSigma = (double) spSigma.getValue();
                userMedianRadius = (double) spMedian.getValue();
                userThresholdMethod = (String) cbSegmentation.getSelectedItem();
                userParticleThresh = (double) spThreshParticles.getValue();
                userRingRadius = (double) spRingRadius.getValue();

                if (opt == JOptionPane.YES_OPTION){
                    saveUserDefinedProcessingParams(isDefaultSigma,
                            isDefaultMedianRadius,
                            isDefaultThresholdMethod,
                            isDefaultParticleThresh,
                            isDefaultRingRadius,
                            userSigma,
                            userMedianRadius,
                            userThresholdMethod,
                            userParticleThresh,
                            userRingRadius);
                }
            }
        }

        if(this.imageForLivePreview != null) {
            this.imageForLivePreview.close();
            this.imageForLivePreview = null;
        }
    }


    /**
     * Create small GUI to select the serie to open
     * @param nSeries number of series
     * @return the selected serie
     */
    private int createSelectSerieGui(int nSeries){
        int serie = 1;

        // build radio buttons
        List<JRadioButton> seriesChoice = new ArrayList<>();
        ButtonGroup rbChoiceGroup = new ButtonGroup();
        for(int i = 0; i < nSeries; i++){
            JRadioButton rb = new JRadioButton("Serie "+(i+1));
            rbChoiceGroup.add(rb);
            rb.setFont(stdFont);
            rb.setSelected(false);
            seriesChoice.add(rb);
        }

        seriesChoice.get(0).setSelected(true);

        // build everything together
        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);
        JPanel settingsPane = new JPanel();

        int settingsRow = 0;
        settingsPane.setLayout(new GridBagLayout());

        for(JRadioButton selectedSerie : seriesChoice){
            constraints.gridx = 0;
            constraints.gridy = settingsRow++;
            settingsPane.add(selectedSerie, constraints);
        }

        // create the general panel
        JOptionPane pane = new JOptionPane(settingsPane, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
                null, null);
        settingsDialog = pane.createDialog(mainDialog, "Select serie to open");

        pane.selectInitialValue();
        settingsDialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        settingsDialog.setVisible(true);
        settingsDialog.dispose();

        Object selectedValue = pane.getValue();
        int opt = JOptionPane.CLOSED_OPTION;

        if(selectedValue instanceof Integer)
            opt = (Integer) selectedValue;

        if(opt == JOptionPane.OK_OPTION){
            for(int i = 0; i < nSeries; i++){
                if(seriesChoice.get(i).isSelected()){
                    serie = i+1;
                    break;
                }
            }
        }
        return serie;
    }

    /**
     * set the default values for each metrics use for processing by reading the corresponding csv file
     */
    private void setDefaultProcessingParams(){
        // read the csv file for processing settings
        Map<String, List<String>> defaultParams = getUserDefinedParams(processingFileName);

        // check and set the validity of each metrics
        Double val = checkAndSetValidityOfReadMetric(defaultParams, sigmaKey, defaultSigma, sigmaUpperBound);
        isDefaultSigma = val > 0;
        userSigma = Math.abs(val);

        val = checkAndSetValidityOfReadMetric(defaultParams, medianKey, defaultMedianRadius, sigmaUpperBound);
        isDefaultMedianRadius = val > 0;
        userMedianRadius = Math.abs(val);

        if(defaultParams.containsKey(segmentationKey) && !(defaultParams.get(segmentationKey).isEmpty() || defaultParams.get(segmentationKey).size() < 2)){
            List<String> values = defaultParams.get(segmentationKey);
            isDefaultThresholdMethod = Boolean.parseBoolean(values.get(0));
            userThresholdMethod = values.get(1);
        }else{
            isDefaultThresholdMethod = true;
            userThresholdMethod = defaultThresholdMethod;
        }

        val = checkAndSetValidityOfReadMetric(defaultParams, threshParticlesKey, defaultParticleThresh, particleThresholdUpperBound);
        isDefaultParticleThresh = val > 0;
        userParticleThresh = Math.abs(val);

        val = checkAndSetValidityOfReadMetric(defaultParams, ringRadiusKey, defaultRingRadius, ringRadiusUpperBound);
        isDefaultRingRadius = val > 0;
        userRingRadius = Math.abs(val);
    }

    /**
     * Test if the metric exists and if it is a {@link Double} value. In case it's not, then default value are
     * assigned instead of the read one.
     *
     * @param metrics list of metrics
     * @param key metric to test
     * @param defaultVal default value in case of bad read
     * @param upperBound maximum allowable value for the metric
     * @return the value of the metric read ; positive if "default" check box was ON, negative otherwise.
     */
    private Double checkAndSetValidityOfReadMetric(Map<String, List<String>> metrics, String key, double defaultVal, int upperBound){
        boolean isDefault;
        double userVal;
        // if the metric exists
        if(metrics.containsKey(key) && !(metrics.get(key).isEmpty() || metrics.get(key).size() < 2)){
            List<String> val = metrics.get(key);
            isDefault = Boolean.parseBoolean(val.get(0));
            // test if the metric is a double value
            try {
                userVal = Double.parseDouble(val.get(1));
                if(userVal < 0 || userVal > upperBound) {
                    userVal = defaultVal;
                    IJLogger.warn("Read default processing params","The value of "+key+" "+val.get(1)+ " is not a range ]0;"+upperBound+"]. Default value is used instead.");
                }
            }catch(Exception e){
                userVal = defaultVal;
                IJLogger.warn("Read default processing params","The value of "+key+" "+val.get(1)+ " is not a numeric value. Default value is used instead.");
            }
        }else{
            isDefault = true;
            userVal = defaultVal;
        }

        return (isDefault ? userVal : -userVal);
    }

    /**
     * set the default values for each metrics use for retrieve and saving data by reading the corresponding csv file
     */
    private void setDefaultGeneralParams(){
        // read the csv file
        Map<String, List<String>> defaultParams = getUserDefinedParams(fileName);

        // assign default or read value
        userHost = defaultParams.containsKey(hostKey) ?
                (defaultParams.get(hostKey).isEmpty() ? defaultHost : defaultParams.get(hostKey).get(0)) :
                defaultHost;
        userPort = defaultParams.containsKey(portKey) ?
                (defaultParams.get(portKey).isEmpty() ? defaultPort : defaultParams.get(portKey).get(0)) :
                defaultPort;
        userArgoSlides = defaultParams.getOrDefault(argoSlidesKey, Collections.emptyList());
        userRootFolder = defaultParams.containsKey(rootFolderKey) ?
                (defaultParams.get(rootFolderKey).isEmpty() ? "" : defaultParams.get(rootFolderKey).get(0)) : "";
        userSaveFolder = defaultParams.containsKey(saveFolderKey) ?
                (defaultParams.get(saveFolderKey).isEmpty() ? "" : defaultParams.get(saveFolderKey).get(0)) : "";
    }

    /**
     * set the default values for ArgoSlide specifications
     */
    private void setDefaultArgoParams(){
        // read the csv file
        Map<String, List<String>> readArgoSlides = getUserDefinedParams(argoSlideFileName);
        Map<String, List<String>> cleanArgoSlides  = new HashMap<>();

        // check all slides
        for(String argoSlideKey : readArgoSlides.keySet()) {
            // get read parameters
            List<String> readArgoSlideSpecs = readArgoSlides.get(argoSlideKey);
            List<String> cleanArgoSlideSpecs = new ArrayList<>();

            // in case parameters have been wrongly read, set to default
            if(readArgoSlideSpecs.isEmpty()){
                IJLogger.warn("ArgoSlide' "+argoSlideKey+"' parameters have not been read correctly (size 0/4). " +
                        "Default values are used instead");
                defaultArgoSlide = "";
                cleanArgoSlideSpecs.add("false");
                cleanArgoSlideSpecs.add(String.valueOf(defaultArgoSpacing));
                cleanArgoSlideSpecs.add(String.valueOf(defaultArgoFoV));
                cleanArgoSlideSpecs.add(String.valueOf(defaultArgoNRings));
            } else {
                if(readArgoSlideSpecs.get(argoDefaultPos).equalsIgnoreCase("true")) {
                    defaultArgoSlide = argoSlideKey;
                }
                if (readArgoSlideSpecs.size() > 3) {
                    cleanArgoSlideSpecs.add(readArgoSlideSpecs.get(argoDefaultPos));
                    cleanArgoSlideSpecs.add(checkAndSetValidityOfReadArgoMetric(readArgoSlideSpecs, argoSpacingPos, defaultArgoSpacing, "ring spacing"));
                    cleanArgoSlideSpecs.add(checkAndSetValidityOfReadArgoMetric(readArgoSlideSpecs, argoFoVPos, defaultArgoFoV, "pattern FoV"));
                    cleanArgoSlideSpecs.add(checkAndSetValidityOfReadArgoMetric(readArgoSlideSpecs, argoNRingsPos, defaultArgoNRings, "Number of rings per line"));
                } else {
                    // in case parameters have been wrongly read, set to default
                    IJLogger.warn("ArgoSlide' "+argoSlideKey+"' parameters have not been read correctly (size "+readArgoSlideSpecs.size()+"/4). " +
                            "Default values are used instead");
                    cleanArgoSlideSpecs.add("false");
                    cleanArgoSlideSpecs.add(String.valueOf(defaultArgoSpacing));
                    cleanArgoSlideSpecs.add(String.valueOf(defaultArgoFoV));
                    cleanArgoSlideSpecs.add(String.valueOf(defaultArgoNRings));
                }
            }
            cleanArgoSlides.put(argoSlideKey, cleanArgoSlideSpecs);
        }

        // replace old parameters
        argoSlidesParameters.clear();
        argoSlidesParameters = cleanArgoSlides;
    }

    /**
     *
     * @param metrics list of read ArgoSlide metrics
     * @param metric the position of the metric to check
     * @param defaultValue the default value for the current metric in case teh read one is not correct
     * @param metricName teh name of the metric tp check
     * @return the metric value as string
     */
    private String checkAndSetValidityOfReadArgoMetric(List<String> metrics, int metric, int defaultValue, String metricName){
        String readArgoSlideSpec = metrics.get(metric);

        // check if correctly read
        if(readArgoSlideSpec == null || readArgoSlideSpec.isEmpty()) {
            IJLogger.warn("Read default ArgoSlide params", "The value of "+metricName+ " "+
                    readArgoSlideSpec + " is not a valid. Default value " +
                    defaultValue+"is used instead.");
            readArgoSlideSpec = String.valueOf(defaultValue);
        }

        // check if the value read is an integer
        try {
            Integer.parseInt(readArgoSlideSpec);
        } catch (Exception e) {
            IJLogger.warn("Read default ArgoSlide params", "The value of "+metricName+ " "+
                    readArgoSlideSpec + " is not a numeric value. Default value " +
                    defaultValue+"is used instead.");
            readArgoSlideSpec = String.valueOf(defaultValue);
        }

        return readArgoSlideSpec;
    }

    /**
     * read the specified csv file
     * @param fileName
     * @return a map of read metrics and the values attached to them.
     */
    private Map<String, List<String>> getUserDefinedParams(String fileName){
        File file = new File(folderName + File.separator + fileName);
        Map<String, List<String>> default_params = new HashMap<>();

        if(!file.exists())
            return Collections.emptyMap();

        try {
            //parsing a CSV file into BufferedReader class constructor
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null){   //returns a Boolean value
                String[] items = line.split(",");
                List<String> values = new ArrayList<>(Arrays.asList(items).subList(1, items.length));
                default_params.put(items[0],values);
            }
            br.close();
            return default_params;
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }


    private void showInfoMessage(String title, String content){
        showMessage(title, content, JOptionPane.INFORMATION_MESSAGE);
    }

    private void showErrorMessage(String title, String content){
        showMessage(title, content, JOptionPane.ERROR_MESSAGE);
    }

    private void showWarningMessage(String title, String content){
        showMessage(title, content, JOptionPane.WARNING_MESSAGE);
    }

    private int showConfirmMessage(String title, String content){
        if(title == null)
            title = "";
        if(content == null)
            content = "";

        return JOptionPane.showConfirmDialog(new JFrame(), content, title, JOptionPane.YES_NO_CANCEL_OPTION);
    }

    private void showMessage(String title, String content, int type){
        if(title == null)
            title = "";
        if(content == null)
            content = "";

        JOptionPane.showMessageDialog(new JFrame(), content, title, type);
    }

    /**
     * read the csv file containing a list of microscopes used.
     * The format should be : one microscope per line
     *
     * @param file
     * @return
     */
    private List<String> parseUserCSV(File file){
        List<String> items = new ArrayList<>();
        try {
            //parsing a CSV file into BufferedReader class constructor
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null){   //returns a Boolean value
                items.add(line.replaceAll("\ufeff", "").replaceAll("ï»¿",""));
            }
            br.close();
            return items;
        } catch (IOException e) {
            showWarningMessage("CSV parsing","Couldn't parse the csv file. No default microscopes to add");
            return Collections.emptyList();
        }
    }

    /**
     * Write a csv file containing all user-defined general parameters
     *
     * @param host
     * @param port
     * @param argoSlides
     * @param rootFolder
     * @param savingFolder
     */
    private void saveUserDefinedGeneralParams(String host, String port, String argoSlides, String rootFolder, String savingFolder) {
        File directory = new File(folderName);

        if(!directory.exists())
            directory.mkdir();

        try {
            File file = new File(directory.getAbsoluteFile() + File.separator + fileName);
            // write the file
            BufferedWriter buffer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            buffer.write(hostKey+","+host + "\n");
            buffer.write(portKey+","+port + "\n");
            buffer.write(argoSlidesKey +","+argoSlides + "\n");
            buffer.write(rootFolderKey+","+rootFolder + "\n");
            buffer.write(saveFolderKey+","+savingFolder + "\n");

            // close the file
            buffer.close();

        } catch (IOException e) {
            showWarningMessage("CSV writing","Couldn't write the csv for default parameters.");
        }
    }

    /**
     * Write a csv file containing all user-defined processing parameters
     *
     * @param isDefaultSigma
     * @param isDefaultMedian
     * @param isDefaultSegMed
     * @param isDefaultParticleThresh
     * @param isDefaultRingRadius
     * @param sigma
     * @param median
     * @param thresholdingMethod
     * @param particleThreshold
     * @param ringRadius
     */
    private void saveUserDefinedProcessingParams(boolean isDefaultSigma, boolean isDefaultMedian, boolean isDefaultSegMed,
                                                 boolean isDefaultParticleThresh, boolean isDefaultRingRadius, double sigma,
                                                 double median, String thresholdingMethod, double particleThreshold,
                                                 double ringRadius) {
        File directory = new File(folderName);

        if(!directory.exists())
            directory.mkdir();

        try {
            File file = new File(directory.getAbsoluteFile() + File.separator + processingFileName);
            // write the file
            BufferedWriter buffer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            buffer.write(sigmaKey+","+ isDefaultSigma+","+sigma + "\n");
            buffer.write(medianKey+","+ isDefaultMedian+","+median + "\n");
            buffer.write(segmentationKey+","+ isDefaultSegMed+","+thresholdingMethod + "\n");
            buffer.write(threshParticlesKey+","+ isDefaultParticleThresh+","+particleThreshold + "\n");
            buffer.write(ringRadiusKey+","+ isDefaultRingRadius+","+ringRadius + "\n");

            // close the file
            buffer.close();

        } catch (IOException e) {
            showWarningMessage("CSV writing","Couldn't write the csv for default parameters.");
        }
    }

    /**
     * Write a csv file containing all user-defined ArgoSlides parameters
     *
     * @param argoSlide
     * @param argoSpacing
     * @param argoFov
     * @param argoNRings
     * @param isDefault
     * @return
     */
    private  Map<String, List<String>> saveUserDefinedArgoSlideParams(String argoSlide, int argoSpacing,
                                                                      int argoFov, int argoNRings, boolean isDefault) {
        File directory = new File(folderName);

        if(!directory.exists())
            directory.mkdir();

        // create a tmp mao to be able to modify it
        Map<String, List<String>> tempMap = new HashMap<>(argoSlidesParameters);

        // set all ArgoSlides to NO-DEFAULT in case of setting the current one to default
        if(isDefault){
            tempMap.forEach((key, value)->{
                value.set(0, "false");
            });
        }

        // create a new entry for the current argoSlide with the new parameters
        List<String> argoslideParamList = new ArrayList<>();
        argoslideParamList.add(String.valueOf(isDefault));
        argoslideParamList.add(String.valueOf(argoSpacing));
        argoslideParamList.add(String.valueOf(argoFov));
        argoslideParamList.add(String.valueOf(argoNRings));
        tempMap.put(argoSlide, argoslideParamList);

        try {
            File file = new File(directory.getAbsoluteFile() + File.separator + argoSlideFileName);
            // write the file
            BufferedWriter buffer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            for(String argoSlideKey : tempMap.keySet()){
                String argoSlideParamCSV = String.join(",", tempMap.get(argoSlideKey));
                buffer.write(argoSlideKey+","+ argoSlideParamCSV + "\n");
            }
            // close the file
            buffer.close();

        } catch (IOException e) {
            showWarningMessage("CSV writing","Couldn't write the csv for ArgoSlide '"+argoSlide+"' parameters.");
        }
        return tempMap;
    }


    public static void main(String... args){
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
    }

    @Override
    public void run() {
        IJLogger.info("ArgoLight Analysis Tool Launched");
        createGui();
    }
}
