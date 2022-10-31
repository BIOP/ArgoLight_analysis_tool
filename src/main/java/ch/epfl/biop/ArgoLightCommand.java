package ch.epfl.biop;

import fr.igred.omero.Client;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.repository.ProjectWrapper;
import net.imagej.ImageJ;
import org.scijava.Initializable;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>ArgoLight")
public class ArgoLightCommand extends DynamicCommand implements Command, Initializable {

    private final static Logger logger = LoggerFactory.getLogger(ArgoLightCommand.class);

    @Parameter(label = "OMERO host")
    String host;

    @Parameter(label = "Enter your gaspar username")
    String username;

    @Parameter(label = "Enter your gaspar password", style = "password", persist = false)
    String password;

    @Parameter(label="Dataset name")
    String microscope;


    static int port = 4064;
    static long argoLightProjectId = 663;
    static String argoSlideName = "Argo-SIM v2.0";



    @Override
    public void run() {

        Client client = new Client();

        // connect to OMERO
        try {
            client.connect(host, port, username, password.toCharArray());
            logger.error("Successful connection to OMERO");
        } catch (ServiceException e) {
            logger.error("Cannot connect to OMERO");
            throw new RuntimeException(e);
        }


        try{
            // get the ArgoSim project
            ProjectWrapper project_wpr = client.getProject(argoLightProjectId);

            // get the specified dataset
            List<DatasetWrapper> datasetWrapperList = project_wpr.getDatasets().stream().filter(e -> e.getName().contains(microscope)).collect(Collectors.toList());

            if(datasetWrapperList.size() == 1) {
                DatasetWrapper datasetWrapper = datasetWrapperList.get(0);
                System.out.println("Image will be downloaded from dataset : "+datasetWrapper.getName());

                // filter all new images
                List<ImageWrapper> imageWrapperList = DataManagement.filterNewImages(client, datasetWrapper);

                // run analysis
                imageWrapperList.forEach(imageWrapper-> ImageProcessing.runAnalysis(client, imageWrapper,datasetWrapper, argoSlideName));

            }


        } catch (ServiceException | ExecutionException | AccessException e) {
            throw new RuntimeException(e);
        }finally {
            System.out.println("Disconnection");
            logger.info("Disconnect from OMERO");
            client.disconnect();
        }

    }


    public static void main(String[] args) throws ExecutionException, InterruptedException {
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();

        //ij.command().run(ArgoLight.class, true).get();
    }


    @Override
    public void initialize() {
        final MutableModuleItem<String> microscopes = //
                getInfo().getMutableInput("microscope", String.class);
        List<String> names = new ArrayList<>();
        names.add("LSM700_INT2");
        names.add("LSM700_INT1");
        names.add("LSM980");
        microscopes.setChoices(names);
    }

}

