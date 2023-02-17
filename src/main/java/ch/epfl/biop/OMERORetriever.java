package ch.epfl.biop;

import fr.igred.omero.Client;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.repository.ProjectWrapper;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class OMERORetriever {

    public static List<ImageWrapper> getImages(Client client, long datasetId, boolean filterRawImages) {
        try {
            // get dataset
            DatasetWrapper datasetWrapper = client.getDataset(datasetId);

            // get children images
            List<ImageWrapper> imageWrapperList = datasetWrapper.getImages(client);

            // filter images
            return filterImages(client, imageWrapperList, filterRawImages);
        } catch(AccessException | ServiceException | ExecutionException e){
            IJLogger.error("Retrieve OMERO images","Cannot retrieve images in dataset "+datasetId);
            return Collections.emptyList();
        }
    }

    public static List<ImageWrapper> getImages(Client client, long projectId, String datasetName, boolean filterRawImages) {
        try {
            // get the ArgoSim project
            ProjectWrapper project_wpr = client.getProject(projectId);

            // get the specified dataset
            List<DatasetWrapper> datasetWrapperList = project_wpr.getDatasets().stream().filter(e -> e.getName().contains(datasetName)).collect(Collectors.toList());

            if (datasetWrapperList.size() == 1) {
                DatasetWrapper datasetWrapper = datasetWrapperList.get(0);
                IJLogger.info("Images downloaded from dataset : " + datasetWrapper.getName());

                List<ImageWrapper> imageWrapperList = datasetWrapper.getImages(client);
                return filterImages(client, imageWrapperList, filterRawImages);
            } else if(datasetWrapperList.isEmpty())
                IJLogger.warn("Project "+project_wpr.getName()+ "("+projectId+") does not contain any dataset with name *"+datasetName+"*");
            else IJLogger.warn("More than one dataset refer to "+datasetName+" Please, group these datasets or change their name.");

        } catch(AccessException | ServiceException | ExecutionException e){
            IJLogger.error("Retrieve OMERO images","Cannot retrieve images in project "+projectId+", dataset *"+datasetName+"*");
        }
        return Collections.emptyList();
    }

    /**
     * return the list of all images that have never been processed yet.
     *
     * @param client
     * @param imageWrapperList
     * @param filterRawImages
     * @return
     */
    public static List<ImageWrapper> filterImages(Client client, List<ImageWrapper> imageWrapperList, boolean filterRawImages) {
        // get all images without the tags "raw" nor "process" and remove macro images from vsi files.
        return imageWrapperList.stream().filter(e-> {
            try {
                if(filterRawImages)
                    return (e.getTags(client).stream().noneMatch(t->(t.getName().equals("raw")||t.getName().equals("processed")))
                            && !(e.getName().contains("[macro image]")));
                else
                    return (e.getTags(client).stream().noneMatch(t->t.getName().equals("processed"))
                            && !(e.getName().contains("[macro image]")));
            } catch (ServiceException | AccessException | ExecutionException ex) {
                throw new RuntimeException(ex);
            }
        }).collect(Collectors.toList());
    }

}
