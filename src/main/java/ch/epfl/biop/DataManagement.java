package ch.epfl.biop;

import fr.igred.omero.Client;
import fr.igred.omero.annotations.MapAnnotationWrapper;
import fr.igred.omero.annotations.TableWrapper;
import fr.igred.omero.annotations.TagAnnotationWrapper;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.GenericRepositoryObjectWrapper;
import fr.igred.omero.repository.ImageWrapper;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.measure.ResultsTable;
import omero.ServerError;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.ImageData;
import omero.gateway.model.ObjectiveData;
import omero.gateway.model.TagAnnotationData;
import omero.model.Microscope;
import omero.model.NamedValue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class DataManagement {

    static String argoSlideName = "Argo-SIM v2.0";

    /**
     * return the list of all images, inside the current dataset, that have never been processed yet.
     *
     * @param client
     * @param datasetWrapper
     * @return
     * @throws AccessException
     * @throws ServiceException
     * @throws ExecutionException
     */
    public static List<ImageWrapper> filterNewImages(Client client, DatasetWrapper datasetWrapper) throws AccessException, ServiceException, ExecutionException {
        // get all images without the tags "raw" nor "process" and remove macro images from vsi files.
        return datasetWrapper.getImages(client).stream().filter(e-> {
            try {
                return (e.getTags(client).stream().noneMatch(t->(t.getName().equals("raw")||t.getName().equals("processed")))
                        && !(e.getName().contains("[macro image]")));
            } catch (ServiceException | AccessException | ExecutionException ex) {
                throw new RuntimeException(ex);
            }
        }).collect(Collectors.toList());
    }


    /**
     * remove the extension from an image name
     *
     * @param name
     * @return
     */
    public static String getNameWithoutExtension(String name){
        int pos = name.lastIndexOf(".");
        if (pos > 0) {
            name = name.substring(0, pos);
        }

        return name;
    }


    /**
     * add a list of tags to an image on OMERO
     * @param client
     * @param imageWrapper
     * @param tags
     */
    public static void addTags(Client client, ImageWrapper imageWrapper, List<String> tags){
        tags.forEach(tag->addTag(client,imageWrapper,tag));
    }


    /**
     * add a tag to an image on OMERO
     *
     * @param client
     * @param imageWrapper
     * @param tag
     */
    public static void addTag(Client client, ImageWrapper imageWrapper, String tag) {
        try {
            // get the corresponding tag in the list of available tags if exists
            List<TagAnnotationWrapper> rawTag = client.getTags().stream().filter(t -> t.getName().equals(tag)).collect(Collectors.toList());

            // check if the tag is already applied to the current image
            boolean isTagAlreadyExists = imageWrapper.getTags(client)
                    .stream()
                    .anyMatch(t -> t.getName().equals(tag));

            // add the tag to the current image if it is not already the case
            if (!isTagAlreadyExists) {
                imageWrapper.addTag(client, rawTag.isEmpty() ? new TagAnnotationWrapper(new TagAnnotationData(tag)) : rawTag.get(0));
                IJ.log("[INFO] [DataManagement][addTag] -- The tag " + tag + " has been successfully applied on the image " + imageWrapper.getId());
            } else IJ.log("[INFO] [DataManagement][addTag] -- The tag " + tag + " is already applied on the image " + imageWrapper.getId());

        }catch (ServiceException | OMEROServerError | AccessException | ExecutionException e){
            IJ.log("[ERROR] [DataManagement][addTag] -- The tag " + tag + " could not be applied on the image " + imageWrapper.getId());
        }
    }


    /**
     * add a list of key-values to an image on OMERO
     *
     * @param client
     * @param imageWrapper
     * @param keyValues
     */
    public static void addKeyValues(Client client, ImageWrapper imageWrapper, List<NamedValue> keyValues){
        // create a new MapAnnotation
        MapAnnotationWrapper newKeyValues = new MapAnnotationWrapper(keyValues);
        newKeyValues.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation");
        try {
            // upload key-values on OMERO
            imageWrapper.addMapAnnotation(client, newKeyValues);
        }catch(ServiceException | AccessException | ExecutionException e){
            IJ.log("[ERROR] [DataManagement][addKeyValues] -- KeyValues could not be added on the image " + imageWrapper.getId());
        }
    }

    /**
     * create all key-value pairs necessary for the project based on the dataset and the image name, as well as image metadata.
     *
     * @param client
     * @param imageWrapper
     * @param datasetWrapper
     * @param microscope
     * @param processingParameters
     * @return
     */
    public static List<NamedValue> generateKeyValuesForProject(Client client, ImageWrapper imageWrapper, DatasetWrapper datasetWrapper, String microscope, Map<String,String> processingParameters){
        // parse the image and dataset name
        String [] imgNameSplit = imageWrapper.getName().split("_");
        String [] datasetNameSplit = datasetWrapper.getName().split("_");

        List<NamedValue> namedValues = new ArrayList<>();

        // get acquisition date
        namedValues.add(new NamedValue("Acquisition_Date", imageWrapper.asImageData().getAcquisitionDate().toLocalDateTime().toString().substring(0,9)));

        // WARNING: check for nulls beforehand (i, m, getModel(), ...)
        // image and dataset name have to be properly formatted
        // dataset name = CODE_Manufacturer_MicroscopeName
        // image name = MicroscopeName_objective_immersion_pattern_date_imageNumber
        if(imgNameSplit.length < 6 || datasetNameSplit.length < 2) {
            IJ.log("[WARN] [DataManagement][generateKeyValues] -- Image name is not written correctly. Please check image name convention");
        } else{
            // get microscope name and manufacturer
            try{
                Microscope m = client.getGateway().getMetadataService(client.getCtx())
                        .loadInstrument(imageWrapper.asImageData()
                                .getInstrumentId())
                                .getMicroscope();

                String model = m.getModel().getValue();
                String manufacturer = m.getManufacturer().getValue();
                namedValues.add(new NamedValue("Microscope", model));
                namedValues.add(new NamedValue("Manufacturer", manufacturer));

            }catch(NullPointerException | DSOutOfServiceException | ServerError e){
                IJ.log("[WARN] [DataManagement][generateKeyValues] -- Not able to read microscope metadata ; default default settings imported");
                namedValues.add(new NamedValue("Microscope", microscope));
                namedValues.add(new NamedValue("Manufacturer", datasetNameSplit[1]));
            }

            // get microscope internal code
            namedValues.add(new NamedValue("Intern_Mic_Code", datasetNameSplit[0]));
            // get the ArgoSlide pattern
            namedValues.add(new NamedValue("Argosim_pattern", imgNameSplit[3]));
        }

        // get the ArgoSlide name
        namedValues.add(new NamedValue("Argoslide_name", argoSlideName));


        // get objective information (type, immersion, magnification and NA)
        try{
            ObjectiveData objective = client.getMetadata().getImageAcquisitionData(client.getCtx(), imageWrapper.getId()).getObjective();
            namedValues.add(new NamedValue("Objective", objective.getModel()));
            //namedValues.add(new NamedValue("Obj_Manufacturer", imgData.getObjective().getManufacturer()))
            namedValues.add(new NamedValue("Magnification", ""+objective.getNominalMagnification()));
            namedValues.add(new NamedValue("NA", ""+objective.getLensNA()));

            if(objective.getImmersion().equals("Other"))
                namedValues.add(new NamedValue("Immersion", imgNameSplit[2]));
            else
                namedValues.add(new NamedValue("Immersion", objective.getImmersion()));

        }catch(NullPointerException | DSOutOfServiceException | ExecutionException | DSAccessException e){
            IJ.log("[WARN] [DataManagement][generateKeyValues] -- Not able to read objective metadata");
        }

        // add all processing parameters
        processingParameters.forEach((key, value) -> namedValues.add(new NamedValue(key, value)));

        return namedValues;
    }


    /**
     * create a new OMERO table with the imageJ ResultTable linked to a repository object.
     *
     * @param client
     * @param repoWrapper
     * @param rowId
     * @param rt
     * @param title
     */
    public static void generateSummaryTable(Client client, GenericRepositoryObjectWrapper<?>  repoWrapper, long rowId, ResultsTable rt, String title, String folder){
        try{
            // get the tables that are already attached to the image/dataset
            TableWrapper tableWrapper = getTable(client, repoWrapper);

            // if previous tables, add results to the table
            if (tableWrapper != null){
                tableWrapper.addRows(client, rt , rowId, new ArrayList<>(0));
                tableWrapper.setName(title);
                replaceTable(client, repoWrapper, tableWrapper);
                addTableAsCSV(client, repoWrapper, tableWrapper, folder);
            }else{
                //if no previous tables, create a new one and add it on OMERO
                tableWrapper = new TableWrapper(client, rt , rowId, new ArrayList<>(0));
                tableWrapper.setName(title);
                addTable(client, repoWrapper, tableWrapper);
                addTableAsCSV(client, repoWrapper, tableWrapper, folder);
            }
        }catch(ServiceException | AccessException | ExecutionException e){
            IJ.log("[ERROR] [DataManagement][generateSummaryTable] -- Could not generate an OMERO table for " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
        }
    }


    /**
     * get the last table attached to an image/dataset/project...
     *
     * @param client
     * @param repoWrapper
     * @return
     */
    public static TableWrapper getTable(Client client, GenericRepositoryObjectWrapper<?>  repoWrapper) {
        try {
            // Prepare a Table
            List<TableWrapper> repoTables = repoWrapper.getTables(client);

            if (!repoTables.isEmpty()) {
                // take the last table
                return repoTables.get(repoTables.size() - 1);
            } else
                return null;
        }catch(ServiceException | AccessException | ExecutionException e){
            IJ.log("[ERROR] [DataManagement][getTable] -- Could not get table attached to " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
            return null;
        }
    }


    /**
     * replace the table by a new one. tableWrapper has the id of the previous table to update but already contains new values.
     *
     * @param client
     * @param repoWrapper
     * @param tableWrapper
     */
    public static void replaceTable(Client client, GenericRepositoryObjectWrapper<?>  repoWrapper, TableWrapper tableWrapper){
        try {
            // create the new table
            TableWrapper newTable = new TableWrapper(tableWrapper.createTable());
            newTable.setName(tableWrapper.getName());

            // add the new table
            repoWrapper.addTable(client, newTable);

            // delete the previous table
            client.deleteFile(tableWrapper.getId());
            IJ.log("[INFO] [DataManagement][replaceTable] -- Table successfully updated for " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
        }catch(ServiceException | AccessException | OMEROServerError | ExecutionException | InterruptedException e){
            IJ.log("[ERROR] [DataManagement][replaceTable] -- Could not update table attached to " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
        }
    }


    /**
     * add a new OMERO table
     *
     * @param client
     * @param repoWrapper
     * @param tableWrapper
     */
    public static void addTable(Client client, GenericRepositoryObjectWrapper<?>  repoWrapper, TableWrapper tableWrapper){
        try {
            repoWrapper.addTable(client, tableWrapper);
            IJ.log("[INFO] [DataManagement][addTable] -- Table successfully added for " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
        }catch(ServiceException | AccessException | ExecutionException e){
            IJ.log("[ERROR] [DataManagement][addTable] -- Could not update table attached to " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
        }
    }

    /**
     * convert a list of images into one an image stack (list of n images ===> one image with n slices)
     *
     * @param imps
     * @return
     */
    private static ImagePlus convertImageListToImageStack(List<ImagePlus> imps){
        // get distinct image titles
        List<String> imagesTitle = imps.stream().map(ImagePlus::getTitle).distinct().collect(Collectors.toList());
        String name = "";

        // get the first image title
        if(imagesTitle.size() > 1)
            name = imagesTitle.get(0).split("_")[0];
        else name = imagesTitle.get(0);

        // create an image stack
        ImagePlus imp = IJ.createHyperStack(name,imps.get(0).getWidth(), imps.get(0).getHeight(),1,imps.size(),1,imps.get(0).getBitDepth());
                //IJ.createImage(name, ImageProcessing.heatMapBitDepth, imps.get(0).getWidth(), imps.get(0).getHeight(), imps.get(0).getBitDepth());

        for(int i = 0; i < imps.size(); i++){
            imp.setPosition(1,i+1,1);
            imp.setProcessor(imps.get(i).getProcessor());
        }

        return imp;
    }


    /**
     * save a list of images locally on the computer
     *
     * @param imps
     * @param imageName
     * @param folder
     */
    public static void saveHeatMapsLocally(List<ImagePlus> imps, String imageName, String folder){
        saveHeatMapLocally(convertImageListToImageStack(imps), imageName, folder);
    }


    /**
     * save an image locally on the computer and returns the saved file
     *
     * @param imp
     * @param imageName
     * @param folder
     * @return
     */
    public static File saveHeatMapLocally(ImagePlus imp, String imageName, String folder){
        FileSaver fs = new FileSaver(imp);
        // create an image file in the given folder, with the given imageName
        File analysisImage_output_path = new File(folder, imageName + "_" + imp.getTitle() + ".tif");

        // save the image
        boolean hasBeenSaved = fs.saveAsTiff(analysisImage_output_path.toString());

        // check if the image was correctly saved
        if(hasBeenSaved) IJ.log("[INFO] [DataManagement][saveHeatMapLocally] -- "+imp.getTitle()+".tif"+" was saved in : "+ folder);
        else IJ.log("[ERROR] [DataManagement][saveHeatMapLocally] -- Cannot save "+imp.getTitle()+ " in "+folder);

        return analysisImage_output_path;
    }


    /**
     * upload images on OMERO, in the given dataset.
     *
     * @param client
     * @param datasetWrapper
     * @param imps
     * @param imageName
     * @param folder
     */
    public static void uploadHeatMaps(Client client, DatasetWrapper datasetWrapper, List<ImagePlus> imps, String imageName, String folder){
        uploadHeatMap(client, datasetWrapper, convertImageListToImageStack(imps), imageName, folder);
    }


    /**
     * upload an image on OMERO, in the given dataset.
     *
     * @param client
     * @param datasetWrapper
     * @param imp
     * @param imageName
     * @param folder
     */
    public static void uploadHeatMap(Client client, DatasetWrapper datasetWrapper, ImagePlus imp, String imageName, String folder){
        // save the image on the computer first and get the generate file
        File localImageFile = saveHeatMapLocally(imp, imageName, folder);
        try {
            // Import image on OMERO
            List<Long> analysisImage_omeroID = client.getDataset(datasetWrapper.getId()).importImage(client, localImageFile.toString());
            ImageWrapper analysisImage_wpr = client.getImage(analysisImage_omeroID.get(0));
            IJ.log("[INFO] [DataManagement][uploadHeatMap] --"+imp.getTitle()+".tif"+" was uploaded to OMERO with ID : "+ analysisImage_omeroID);

            // add tags to the newly created image
            addTag(client, analysisImage_wpr, "processed");
            addTag(client, analysisImage_wpr, imp.getTitle().split(" ")[0]);
            addTag(client, analysisImage_wpr, "argolight");

        }catch (ServiceException | AccessException | ExecutionException | OMEROServerError e){
            IJ.log("[ERROR] [DataManagement][uploadHeatMap] -- Cannot upload heat maps on OMERO");
        } finally{
            // delete the file after upload
            boolean hasBeenDeleted = localImageFile.delete();
            if(hasBeenDeleted) IJ.log("[INFO] [DataManagement][uploadHeatMap] -- Temporary image deleted");
            else IJ.log("[ERROR] [DataManagement][uploadHeatMap] -- Cannot delete temporary saved image");
        }
    }


    /**
     * build a ResultTable from a TableWrapper
     *
     * @param tableWrapper
     * @return
     */
    private static ResultsTable getResultTableFromTableWrapper(TableWrapper tableWrapper){
        ResultsTable rt_image = new ResultsTable();

        // get results and table size from OMERO table
        Object[][] data = tableWrapper.getData();
        int nbCol = tableWrapper.getColumnCount();
        int nbRow = tableWrapper.getRowCount();

        // build the ResultsTable
        if(nbCol > 2){ // if we have more than a label to display

            for(int i = 0; i < nbRow; i++){
                rt_image.incrementCounter();
                // add the image IDs at the end of the table to be compatible with omero.parade
                ImageData imageData = (ImageData) (data[0][i]);
                rt_image.setValue(tableWrapper.getColumnName(0), i, imageData.getId());  // this is very important to get the image ids and be omero.parade compatible

                // populate the resultsTable
                for(int j = 1 ; j < nbCol; j++){
                    if(data[j][i] instanceof String)
                        rt_image.setValue(tableWrapper.getColumnName(j), i, (String)data[j][i]);
                    else  rt_image.setValue(tableWrapper.getColumnName(j), i, (double)data[j][i]);
                }
            }
        }

        return rt_image;
    }


    /**
     * add a ResultsTable as CSV file on OMERO
     *
     * @param client
     * @param repoWrapper
     * @param tableWrapper
     * @param folder
     */
    private static void addTableAsCSV(Client client, GenericRepositoryObjectWrapper<?>  repoWrapper, TableWrapper tableWrapper, String folder){
        // generate an imageJ ResultsTable from an OMERO table
        ResultsTable rt = getResultTableFromTableWrapper(tableWrapper);

        // save locally the ResultsTable as a csv file.
        File localTableFile = new File(folder, tableWrapper.getName().replace(" ","_") + ".csv");
        rt.save(localTableFile.toString());

        try{
            // get the number of existing files
            int nFile = repoWrapper.getFileAnnotations(client).size();

            // Import csv table on OMERO
            long fileID = repoWrapper.addFile(client, localTableFile);

            // test if all csv files are imported
            if(repoWrapper.getFileAnnotations(client).size() == nFile + 1)
                IJ.log("[INFO] [DataManagement][addTableAsCSV] -- The imageJ Results table has been successfully imported on OMERO with id : "+fileID);
		    else
                IJ.log("[ERROR] [DataManagement][addTableAsCSV] -- The imageJ Results table has not been imported for some reasons");

        } catch (ServiceException | AccessException | ExecutionException | InterruptedException e){
            IJ.log("[ERROR] [DataManagement][addTableAsCSV] -- Cannot upload imageJ Results table on OMERO");
        } finally{
            // delete the file after upload
            boolean hasBeenDeleted = localTableFile.delete();
            if(hasBeenDeleted) IJ.log("[INFO] [DataManagement][addTableAsCSV] -- Temporary table deleted");
            else IJ.log("[ERROR] [DataManagement][addTableAsCSV] -- Cannot delete temporary saved table");
        }
    }
}
