package ch.epfl.biop;

import fr.igred.omero.Client;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.repository.ProjectWrapper;
import ij.IJ;
import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>ArgoLight analysis tool")
public class ArgoLightCommand extends DynamicCommand implements Command {

    @Parameter(label = "OMERO host")
    String host;

    @Parameter(label = "Enter your gaspar username")
    String username;

    @Parameter(label = "Enter your gaspar password", style = "password", persist = false)
    String password;

    @Parameter(label="Dataset name",choices={"LSM700_INT2","LSM700_INT1","LSM700_UP2","LSM710", "SD_W1","SP8_FLIM","STED_3X","SP8_UP1",
    "SP8_UP2","SP8_INT","CSU_W1","LSM980","CELLXCELLENCE","AXIOPLAN","STEREOLOGY","SIM-STORM","SLIDESCANNER_1","SLIDESCANNER_2",
    "LATTICE_LIGHTSHEET","LIGHTSHEET_Z1","PALM_MICROBEAM","OPERETTA_CLS"})
    String microscope;

    @Parameter(label="Saving options",choices={"No heat maps saving","Save heat maps locally","Save heat maps in OMERO"})
    String savingOption;

    @Parameter(label="Saving folder",style="directory")
    File folder;

    static int port = 4064;
    static long argoLightProjectId = 663;


    @Override
    public void run() {
        Client client = new Client();

        // connect to OMERO
        try {
            client.connect(host, port, username, password.toCharArray());
            IJ.log("[INFO] [ArgoLightCommand][run] -- Successful connection to OMERO");
        } catch (ServiceException e) {
            IJ.log("[ERROR] [ArgoLightCommand][run] -- Cannot connect to OMERO");
            throw new RuntimeException(e);
        }

        try{
            // get the ArgoSim project
            ProjectWrapper project_wpr = client.getProject(argoLightProjectId);

            // get the specified dataset
            List<DatasetWrapper> datasetWrapperList = project_wpr.getDatasets().stream().filter(e -> e.getName().contains(microscope)).collect(Collectors.toList());

            if(datasetWrapperList.size() == 1) {
                DatasetWrapper datasetWrapper = datasetWrapperList.get(0);
                IJ.log("[INFO] [ArgoLightCommand][run] -- Image will be downloaded from dataset : "+datasetWrapper.getName());

                // filter all new images
                List<ImageWrapper> imageWrapperList = DataManagement.filterNewImages(client, datasetWrapper);

                // run analysis
                imageWrapperList.forEach(imageWrapper-> ImageProcessing.runAnalysis(client, imageWrapper,datasetWrapper, microscope, savingOption, folder));
            }else if(datasetWrapperList.isEmpty())
                IJ.log("[ERROR] [ArgoLightCommand][run] -- No dataset "+microscope+" can be found in the project "+argoLightProjectId);
            else IJ.log("[ERROR] [ArgoLightCommand][run] -- More than one dataset refer to "+microscope+". Please, group these datasets or change their name.");

        } catch (ServiceException | ExecutionException | AccessException e) {
            throw new RuntimeException(e);
        }finally {
            IJ.log("[INFO] [ArgoLightCommand][run] -- Disconnect from OMERO ");
            client.disconnect();
        }
    }


    public static void main(String[] args) {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
        //DebugTools.enableLogging("DEBUG");
        //ij.command().run(ArgoLight.class, true).get();
    }
}

