package ch.epfl.biop.command;

import ch.epfl.biop.processing.Processing;
import ch.epfl.biop.retrievers.OMERORetriever;
import ch.epfl.biop.senders.LocalSender;
import ch.epfl.biop.senders.OMEROSender;
import ch.epfl.biop.senders.Sender;
import ch.epfl.biop.utils.IJLogger;
import fr.igred.omero.Client;
import fr.igred.omero.exception.ServiceException;
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

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
import javax.swing.JSeparator;
import javax.swing.JTextField;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
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


@Plugin(type = Command.class, menuPath = "Plugins>BIOP>ArgoLight swing gui")
public class ArgoLightSwingGui implements Command {
    private static String defaultHost;
    private static String defaultPort;
    private static String defaultProjectID;
    private List<String> defaultMicroscopes;
    private static String defaultRootFolder;
    private static String defaultSaveFolder;
    final private String hostKey = "OMERO Host";
    final private String portKey = "OMERO Port";
    final private String projectIdKey = "Project ID";
    final private String microscopeKey = "Microscopes";
    final private String saveFolderKey = "Saving folder";
    final private String rootFolderKey = "Root folder";
    final static private String folderName = "." + File.separator + "plugins" + File.separator + "BIOP";
    final static private String fileName = "ArgoLight_default_params.csv";

    final private Font stdFont = new Font("Calibri", Font.PLAIN, 17);
    final private Font titleFont = new Font("Calibri", Font.BOLD, 22);


    private static void runProcessing(boolean isOmeroRetriever, String username, char[] password, String rootFolderPath,
                                      String microscope, boolean isOmeroSender, String savingFolderPath, boolean saveHeatMaps, boolean allImages){

        boolean finalPopupMessage = true;
        if(!isOmeroRetriever && !new File(rootFolderPath).exists()){
            showWarningMessage("Root folder not accessible", "The root folder "+rootFolderPath+" does not exist");
            return;
        }
        if(!isOmeroSender && !new File(savingFolderPath).exists()){
            showWarningMessage("Saving folder not accessible", "The saving folder "+savingFolderPath+" does not exist");
            return;
        }

        Client client = new Client();

        // connect to OMERO
        try {
            client.connect(defaultHost, Integer.parseInt(defaultPort), username, password);
            IJLogger.info("Successful connection to OMERO");
        } catch (ServiceException e) {
            IJLogger.error("Cannot connect to OMERO");
            showErrorMessage("OMERO connections", "OMERO connection fails. Please check host, port and credentials");
            return;
        }

        try {
            OMERORetriever omeroRetriever = new OMERORetriever(client).loadRawImages(Long.parseLong(defaultProjectID), microscope, allImages);
            int nImages = omeroRetriever.getNImages();

            Sender sender;
            if (!isOmeroSender) {
                File savingFolder = new File(savingFolderPath);
                sender = new LocalSender(savingFolder, microscope);
            } else
                sender = new OMEROSender(client, omeroRetriever.getParentTarget());

            // run analysis
            if (nImages > 0)
                Processing.run(omeroRetriever, saveHeatMaps, sender);
            else IJLogger.error("No images are available for project " + defaultProjectID + ", dataset " + microscope);

        } catch (Exception e){
            finalPopupMessage = false;
        } finally {
            client.disconnect();
            IJLogger.info("Disconnected from OMERO ");
        }

        if(finalPopupMessage) {
            showInfoMessage("Processing Done", "All images have been analyzed and results saved");
        }
    }



    public void createGui(){
        String title = "Metrology with ArgoLight plugin";
        JDialog generalPane = new JDialog();

        setDefaultParams();

        // label and text field for OMERO credentials and host
        JLabel labHost = new JLabel (defaultHost+":"+defaultPort);
        labHost.setFont(stdFont);

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

        JLabel labProjectID = new JLabel("Project ID");
        labProjectID.setFont(stdFont);
        JTextField tfProjectID = new JTextField(defaultProjectID);
        tfProjectID.setFont(stdFont);
        tfProjectID.setColumns(15);

        // label and textField to choose root folder in case of local retriever
        JLabel labRootFolder  = new JLabel("Root Folder");
        labRootFolder.setFont(stdFont);
        JTextField tfRootFolder = new JTextField(defaultRootFolder);
        tfRootFolder.setFont(stdFont);
        tfRootFolder.setColumns(15);

        labRootFolder.setEnabled(false);
        tfRootFolder.setEnabled(false);

        // button to choose root folder
        JButton bRootFolder = new JButton("Choose folder");
        bRootFolder.setFont(stdFont);
        bRootFolder.addActionListener(e->{
            JFileChooser directoryChooser = new JFileChooser();
            directoryChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            directoryChooser.setDialogTitle("Choose the microscopes' root folder");
            directoryChooser.showDialog(generalPane,"Select");

            if (directoryChooser.getSelectedFile() != null)
                tfRootFolder.setText(directoryChooser.getSelectedFile().getAbsolutePath());
        });

        bRootFolder.setEnabled(false);

        JLabel labMicroscope = new JLabel("Microscope");
        labMicroscope.setFont(stdFont);
        // build project combo model
        DefaultComboBoxModel<String> modelCmbMicroscope = new DefaultComboBoxModel<>();
        JComboBox<String> cbMicroscope = new JComboBox<>(modelCmbMicroscope);
        cbMicroscope.setFont(stdFont);
        defaultMicroscopes.forEach(cbMicroscope::addItem);

        // label and textField to choose root folder in case of local retriever
        JLabel labSavingFolder  = new JLabel("Saving Folder");
        labSavingFolder.setFont(stdFont);
        JTextField tfSavingFolder = new JTextField(defaultSaveFolder);
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
            directoryChooser.setDialogTitle("Choose the microscopes' root folder");
            directoryChooser.showDialog(generalPane,"Select");

            if (directoryChooser.getSelectedFile() != null)
                tfSavingFolder.setText(directoryChooser.getSelectedFile().getAbsolutePath());

        });
        bSavingFolder.setEnabled(false);

        JCheckBox chkSaveHeatMap = new JCheckBox("Save heat maps");
        chkSaveHeatMap.setSelected(false);
        chkSaveHeatMap.setFont(stdFont);

        JCheckBox chkAllImages = new JCheckBox("Process again existing images");
        chkAllImages.setSelected(false);
        chkAllImages.setFont(stdFont);

        // Radio button to choose local retriever
        ButtonGroup senderChoice = new ButtonGroup();
        JRadioButton rbLocalSender = new JRadioButton("Local");
        rbLocalSender.setFont(stdFont);
        rbLocalSender.setSelected(false);
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
        senderChoice.add(rbOmeroSender);
        rbOmeroSender.addActionListener(e -> {
            tfSavingFolder.setEnabled(!rbOmeroSender.isSelected());
            labSavingFolder.setEnabled(!rbOmeroSender.isSelected());
            bSavingFolder.setEnabled(!rbOmeroSender.isSelected());
        });


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
            tfUsername.setEnabled(rbOmeroRetriever.isSelected());
            tfPassword.setEnabled(rbOmeroRetriever.isSelected());
            labHost.setEnabled(rbOmeroRetriever.isSelected());
            labUsername.setEnabled(rbOmeroRetriever.isSelected());
            labPassword.setEnabled(rbOmeroRetriever.isSelected());
            rbOmeroSender.setEnabled(rbOmeroRetriever.isSelected());
            labProjectID.setEnabled(rbOmeroRetriever.isSelected());
            tfProjectID.setEnabled(rbOmeroRetriever.isSelected());
            tfSavingFolder.setEnabled(!rbOmeroRetriever.isSelected());
            labSavingFolder.setEnabled(!rbOmeroRetriever.isSelected());
            bSavingFolder.setEnabled(!rbOmeroRetriever.isSelected());
            rbOmeroSender.setSelected(rbOmeroRetriever.isSelected());
            rbLocalSender.setSelected(!rbOmeroRetriever.isSelected());
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
            labProjectID.setEnabled(!rbLocalRetriever.isSelected());
            tfProjectID.setEnabled(!rbLocalRetriever.isSelected());
            bRootFolder.setEnabled(rbLocalRetriever.isSelected());
            tfRootFolder.setEnabled(rbLocalRetriever.isSelected());
            labRootFolder.setEnabled(rbLocalRetriever.isSelected());
            rbOmeroSender.setSelected(!rbLocalRetriever.isSelected());
            rbLocalSender.setSelected(rbLocalRetriever.isSelected());
            rbOmeroSender.setEnabled(!rbLocalRetriever.isSelected());
            tfSavingFolder.setEnabled(rbLocalRetriever.isSelected());
            labSavingFolder.setEnabled(rbLocalRetriever.isSelected());
            bSavingFolder.setEnabled(rbLocalRetriever.isSelected());
        });

        // button to choose root folder
        JButton bSettings = new JButton("Settings");
        bSettings.setFont(stdFont);
        bSettings.addActionListener(e->{
            createSettingsPane();
            setDefaultParams();
            labHost.setText(defaultHost+":"+defaultPort);
            cbMicroscope.setSelectedItem(defaultMicroscopes);
            tfProjectID.setText(defaultProjectID);
            tfRootFolder.setText(defaultRootFolder);
            tfSavingFolder.setText(defaultSaveFolder);
        });


        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);

        JPanel omeroPane = new JPanel();
        int omeroRetrieverRow = 0;
        omeroPane.setLayout(new GridBagLayout());

        // label and text field for OMERO credentials and host
        JLabel  retrieverTitle = new JLabel ("Get images from");
        retrieverTitle.setFont(titleFont);

        constraints.gridwidth = 2; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(retrieverTitle, constraints);
        constraints.gridwidth = 1; // set it back

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(rbOmeroRetriever, constraints);

        constraints.gridx = 3;
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

        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow;
        omeroPane.add(labProjectID, constraints);

        constraints.gridx = 1;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(tfProjectID, constraints);

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        // label and text field for OMERO credentials and host
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
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(cbMicroscope, constraints);

        constraints.gridwidth = 4; // span two rows
        constraints.gridx = 0;
        constraints.gridy = omeroRetrieverRow++;
        omeroPane.add(new JSeparator(), constraints);
        constraints.gridwidth = 1; // set it back

        // label and text field for OMERO credentials and host
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
        omeroPane.add(bSettings, constraints);

        int opt = JOptionPane.showConfirmDialog(null, omeroPane, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if(opt == JOptionPane.OK_OPTION){
            char[] password = tfPassword.getPassword();

            runProcessing(rbOmeroRetriever.isSelected(),
                    tfUsername.getText(),
                    password,
                    tfRootFolder.getText(),
                    (String)cbMicroscope.getSelectedItem(),
                    rbOmeroSender.isSelected(),
                    tfSavingFolder.getText(),
                    chkSaveHeatMap.isSelected(),
                    chkAllImages.isSelected());
        }
       // generalPane.setVisible(true);
    }


    private void createSettingsPane(){
        JDialog generalPane = new JDialog();

        // label and text field for OMERO credentials and host
        JLabel labHost = new JLabel("OMERO host");
        labHost.setFont(stdFont);
        JTextField tfHost = new JTextField(defaultHost);
        tfHost.setFont(stdFont);
        tfHost.setColumns(15);

        JLabel labPort = new JLabel("OMERO port");
        labPort.setFont(stdFont);
        JTextField tfPort = new JTextField(defaultPort);
        tfPort.setFont(stdFont);
        tfPort.setColumns(15);

        JLabel labProject = new JLabel("OMERO Project ID");
        labProject.setFont(stdFont);
        JTextField tfProject = new JTextField(defaultProjectID);
        tfProject.setFont(stdFont);
        tfProject.setColumns(15);

        JLabel labMicroscope = new JLabel("Microscopes");
        labMicroscope.setFont(stdFont);
        JTextField tfMicroscope = new JTextField(String.join(",",defaultMicroscopes));
        tfMicroscope.setFont(stdFont);
        tfMicroscope.setColumns(15);

        // button to choose root folder
        JButton bChooseMicroscope = new JButton("Open file");
        bChooseMicroscope.setFont(stdFont);
        bChooseMicroscope.addActionListener(e->{
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fileChooser.setDialogTitle("Choose the microscopes' csv file");
            fileChooser.showDialog(generalPane,"Select");

            if (fileChooser.getSelectedFile() != null) {
                File rootFolder = fileChooser.getSelectedFile();
                if(!rootFolder.exists())
                    showErrorMessage("No files", "The file you selected does not exist. Please check your path / file");
                else if(!rootFolder.getAbsolutePath().endsWith(".csv"))
                    showErrorMessage("Wrong file type", "The file you selected is not a .csv file. Please select a .csv file");
                else {
                    List<String> microscopes = parseMicroscopesCSV(rootFolder);
                    String microscopesList = String.join(",", microscopes);
                    tfMicroscope.setText(microscopesList);
                }
            }
        });

        JLabel labRootFolder = new JLabel("Root folder");
        labRootFolder.setFont(stdFont);
        JTextField tfRootFolder = new JTextField(defaultRootFolder);
        tfRootFolder.setFont(stdFont);
        tfRootFolder.setColumns(15);

        JLabel labSaveFolder = new JLabel("Saving folder");
        labSaveFolder.setFont(stdFont);
        JTextField tfSaveFolder = new JTextField(defaultSaveFolder);
        tfSaveFolder.setFont(stdFont);
        tfSaveFolder.setColumns(15);

        // button to choose root folder
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

        // button to choose root folder
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

        GridBagConstraints constraints = new GridBagConstraints( );
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(5,5,5,5);
        JPanel settingsPane = new JPanel();

        int settingsRow = 0;
        settingsPane.setLayout(new GridBagLayout());

        // label and text field for OMERO credentials and host
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
        settingsPane.add(labProject, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow++;
        settingsPane.add(tfProject, constraints);

        constraints.gridx = 0;
        constraints.gridy = settingsRow;
        settingsPane.add(labMicroscope, constraints);

        constraints.gridx = 1;
        constraints.gridy = settingsRow;
        settingsPane.add(tfMicroscope, constraints);

        constraints.gridx = 2;
        constraints.gridy = settingsRow++;
        settingsPane.add(bChooseMicroscope, constraints);

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
        constraints.gridy = settingsRow++;
        settingsPane.add(bChooseSaveFolder, constraints);

        int opt = JOptionPane.showConfirmDialog(null, settingsPane, "Setup your default settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if(opt == JOptionPane.OK_OPTION){
            buildCSVFile(tfHost.getText(),
                    tfPort.getText(),
                    tfProject.getText(),
                    tfMicroscope.getText(),
                    tfRootFolder.getText(),
                    tfSaveFolder.getText());
        }
    }

    private void setDefaultParams(){
        Map<String, List<String>> defaultParams = getDefaultParams();
        defaultHost = defaultParams.containsKey(hostKey) ?
                (defaultParams.get(hostKey).isEmpty() ? "localhost" : defaultParams.get(hostKey).get(0)) :
                "localhost";
        defaultPort = defaultParams.containsKey(portKey) ?
                (defaultParams.get(portKey).isEmpty() ? "4064" : defaultParams.get(portKey).get(0)) :
                "4064";
        defaultMicroscopes = defaultParams.getOrDefault(microscopeKey, Collections.emptyList());
        defaultProjectID = defaultParams.containsKey(projectIdKey) ?
                (defaultParams.get(projectIdKey).isEmpty() ? "-1" : defaultParams.get(projectIdKey).get(0)) :
                "-1";
        defaultRootFolder = defaultParams.containsKey(rootFolderKey) ?
                (defaultParams.get(rootFolderKey).isEmpty() ? "" : defaultParams.get(rootFolderKey).get(0)) : "";
        defaultSaveFolder = defaultParams.containsKey(saveFolderKey) ?
                (defaultParams.get(saveFolderKey).isEmpty() ? "" : defaultParams.get(saveFolderKey).get(0)) : "";
    }

    private static Map<String, List<String>> getDefaultParams(){
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


    private static void showInfoMessage(String title, String content){
        showMessage(title, content, JOptionPane.INFORMATION_MESSAGE);
    }

    private static void showErrorMessage(String title, String content){
        showMessage(title, content, JOptionPane.ERROR_MESSAGE);
    }

    private static void showWarningMessage(String title, String content){
        showMessage(title, content, JOptionPane.WARNING_MESSAGE);
    }

    private static void showMessage(String title, String content, int type){
        if(title == null)
            title = "";
        if(content == null)
            content = "";

        JOptionPane.showMessageDialog(new JFrame(), content, title, type);
    }

    private static List<String> parseMicroscopesCSV(File file){
        List<String> items = new ArrayList<>();
        try {
            //parsing a CSV file into BufferedReader class constructor
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null){   //returns a Boolean value
                items.add(line.replace("\ufeff", ""));
            }
            br.close();
            return items;
        } catch (IOException e) {
            showWarningMessage("CSV parsing","Couldn't parse the csv file. No default microscopes to add");
            return Collections.emptyList();
        }
    }

    private void buildCSVFile(String host, String port, String projectID, String microscopes, String rootFolder, String savingFolder) {
        File directory = new File(folderName);

        if(!directory.exists())
            directory.mkdir();

        try {
            File file = new File(directory.getAbsoluteFile() + File.separator + fileName);
            // write the file
            BufferedWriter buffer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            buffer.write(hostKey+","+host + "\n");
            buffer.write(portKey+","+port + "\n");
            buffer.write(projectIdKey+","+projectID + "\n");
            buffer.write(microscopeKey+","+microscopes + "\n");
            buffer.write(rootFolderKey+","+rootFolder + "\n");
            buffer.write(saveFolderKey+","+savingFolder + "\n");

            // close the file
            buffer.close();

        } catch (IOException e) {
            showWarningMessage("CSV writing","Couldn't write the csv for default parameters.");
        }
    }

    public static void main(String... args){
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
    }

    @Override
    public void run() {
        createGui();
    }
}
