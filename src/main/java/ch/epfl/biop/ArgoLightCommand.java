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
    static String argoLightName = "Argo-SIM v2.0";



    @Override
    public void run() {

        Client client = new Client();

        try {
            client.connect(host, port, username, password.toCharArray());
        } catch (ServiceException e) {
            logger.error("Cannot connect to OMERO");
            throw new RuntimeException(e);
        }


        try{
            ProjectWrapper project_wpr = client.getProject(argoLightProjectId);
            List<DatasetWrapper> datasets = project_wpr.getDatasets().stream().filter(e -> e.getName().contains(microscope)).collect(Collectors.toList());

            if(datasets.size() == 1) {
                DatasetWrapper datasetWrapper = datasets.get(0);

                // filter by tags
                List<ImageWrapper> imageWrappers = datasetWrapper.getImages(client).stream().filter(e-> {
                        try {
                            return (e.getTags(client).stream().noneMatch(t->(t.getName().equals("raw")||t.getName().equals("processed")))
                                    && !(e.getName().contains("[macro image]")));
                        } catch (ServiceException | AccessException | ExecutionException ex) {
                            throw new RuntimeException(ex);
                        }
                    }).collect(Collectors.toList());

                imageWrappers.forEach(imageWrapper-> DataManagement.processImage(client, imageWrapper, datasetWrapper, argoLightName));

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
        microscopes.setChoices(names);
    }

}

