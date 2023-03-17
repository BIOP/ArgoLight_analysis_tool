package ch.epfl.biop.senders;

import ch.epfl.biop.utils.IJLogger;
import ch.epfl.biop.image.ImageChannel;
import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.utils.Tools;
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
import fr.igred.omero.roi.ROIWrapper;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.Roi;
import ij.io.FileSaver;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.TablesFacility;
import omero.gateway.model.EllipseData;
import omero.gateway.model.ImageData;
import omero.gateway.model.ROIData;
import omero.gateway.model.RectangleData;
import omero.gateway.model.ShapeData;
import omero.gateway.model.TableData;
import omero.gateway.model.TableDataColumn;
import omero.gateway.model.TagAnnotationData;
import omero.model.NamedValue;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public class OMEROSender implements Sender{
    final Client client;
    private ImageWrapper imageWrapper;
    final private static String PROCESSED_FEATURE = "feature";
    private final String datasetId;

    public OMEROSender(Client client, String datasetTarget){
        this.client = client;
        this.datasetId = datasetTarget;
    } //TODO ajouter le boolean "local heat map saving"

    @Override
    public void initialize(ImageFile imageFile, ImageWrapper imageWrapper) {
     this.imageWrapper = imageWrapper;
    }

    @Override
    public void sendTags(List<String> tags, ImageWrapper imageWrapper, Client client) {
        for(String tag : tags) {
            try {
                // get the corresponding tag in the list of available tags if exists
                List<TagAnnotationWrapper> rawTag = client.getTags().stream().filter(t -> t.getName().equals(tag)).collect(Collectors.toList());

                // check if the tag is already applied to the current image
                boolean isTagAlreadyExists = imageWrapper.getTags(client)
                        .stream()
                        .anyMatch(t -> t.getName().equals(tag));

                // add the tag to the current image if it is not already the case
                if (!isTagAlreadyExists) {
                    imageWrapper.link(client, rawTag.isEmpty() ? new TagAnnotationWrapper(new TagAnnotationData(tag)) : rawTag.get(0));
                    IJLogger.info("Adding tag","The tag " + tag + " has been successfully applied on the image " + imageWrapper.getId());
                } else
                    IJLogger.info("Adding tag","The tag " + tag + " is already applied on the image " + imageWrapper.getId());

            } catch (ServiceException | OMEROServerError | AccessException | ExecutionException e) {
                IJLogger.error("Adding tag","The tag " + tag + " could not be applied on the image " + imageWrapper.getId());
            }
        }
    }

    @Override
    /**
     * target : dataset ID
     */
    public void sendHeatMaps(ImagePlus imp) {
        String home = Prefs.getHomeDir();

        // save the image on the computer first and get the generate file
        File localImageFile = saveHeatMapLocally(imp, home);
        if(localImageFile == null) {
            IJLogger.error("Saving temporary heatMap","Cannot save temporary image "+imp.getTitle()+" in " +home);
            return;
        }

        try {
            // Import image on OMERO
            List<Long> analysisImage_omeroID = this.client.getDataset(Long.parseLong(this.datasetId)).importImage(this.client, localImageFile.toString());
            ImageWrapper analysisImage_wpr = this.client.getImage(analysisImage_omeroID.get(0));
            IJLogger.info("Upload heatMap", imp.getTitle() + ".tif" + " was uploaded to OMERO with ID : " + analysisImage_omeroID);

            List<String> tags = new ArrayList<String>(){{
                add("processed");
                add("argolight");
                add((String)imp.getProperty(PROCESSED_FEATURE));
            }};
            sendTags(tags, analysisImage_wpr, this.client);

        } catch (ServiceException | AccessException | ExecutionException | OMEROServerError e) {
            IJLogger.error("Upload heatMap", "Cannot upload heat maps on OMERO");
        }

        // delete the file after upload
        boolean hasBeenDeleted = localImageFile.delete();
        if(hasBeenDeleted)
            IJLogger.info("Upload heatMap","Temporary image deleted");
        else IJLogger.error("Upload heatMap","Cannot delete temporary image from "+localImageFile.getAbsolutePath());
    }

    @Override
    public void sendKeyValues(Map<String, String> keyValues) {
        if(!keyValues.isEmpty()) {
            List<NamedValue> namedValues = new ArrayList<>();
            keyValues.forEach((key, value) -> namedValues.add(new NamedValue(key, value)));

            // create a new MapAnnotation
            MapAnnotationWrapper newKeyValues = new MapAnnotationWrapper(namedValues);
            newKeyValues.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation");
            try {
                // upload key-values on OMERO
                this.imageWrapper.addMapAnnotation(this.client, newKeyValues);
            } catch (ServiceException | AccessException | ExecutionException e) {
                IJLogger.error("Adding Key-Values", "KeyValues could not be added on the image " + this.imageWrapper.getId());
            }
        } else IJLogger.warn("Sending Key-Values", "There is no key-values to send to OMERO");
    }

    @Override
    public void sendGridPoints(List<Roi> rois, int channelId, String roiTitle) {
        // import ROIs on OMERO
        if (!(rois.isEmpty())) {
            List<ShapeData> gridShapes = convertIJRoisToShapeData(rois, channelId, roiTitle);

            ROIData roiGrid = new ROIData();
            gridShapes.forEach(roiGrid::addShapeData);

            List<ROIWrapper> omeroRois = new ArrayList<ROIWrapper>(){{add(new ROIWrapper(roiGrid));}};
            try {
                // save ROIs
                this.imageWrapper.saveROIs(this.client, omeroRois);
            } catch (ExecutionException | DSOutOfServiceException | DSAccessException e){
                IJLogger.error("ROI Saving","Error during saving ROIs on OMERO.");
            }
        } else IJLogger.info("Upload annotations","There is no Annotations to upload on OMERO");

    }

    @Override
    public void sendPCCTable(List<List<Double>> pccValues, int nChannels){
        List<TableDataColumn> columns = new ArrayList<>();
        List<List<Object>> measurements = new ArrayList<>();

        List<Integer> chs1 = new ArrayList<>();
        List<Integer> chs2 = new ArrayList<>();

        for(int i = 0; i < nChannels-1; i++)
            for(int j = i+1; j < nChannels; j++){
                chs1.add(i);
                chs2.add(j);
            }

        int i = 0;
        for(List<Double> channelPair : pccValues){
            columns.add(new TableDataColumn("ch"+chs1.get(i)+"_ch"+chs2.get(i), i++, Double.class));
            measurements.add(new ArrayList<>(channelPair));
        }

        if(!columns.isEmpty()) {
            // send the omero Table
            try {
                TableWrapper tableWrapper = new TableWrapper(new TableData(columns, measurements));
                tableWrapper.setName("PCC_table");
                this.imageWrapper.addTable(client, tableWrapper);
                IJLogger.info("Results table for image " + this.imageWrapper.getName() + " : " + this.imageWrapper.getId() + " has been uploaded");
            } catch (DSAccessException | ServiceException | ExecutionException e) {
                IJLogger.error("Cannot add the results table to image " + this.imageWrapper.getName() + " : " + this.imageWrapper.getId());
            }
        } else IJLogger.warn("Saving PCC table","No results to save");
    }

    @Override
    public void sendResultsTable(List<List<Double>> values, List<Integer> channelIdList, boolean replaceExistingTable, String tableName){
        String date = Tools.getCurrentDateAndHour();
        TableWrapper tableWrapper;

        if(!replaceExistingTable){
            TableWrapper table = getOmeroTable(this.client, this.imageWrapper, tableName);
            if(table != null)
                tableWrapper = addNewColumnsToTable(table, values, channelIdList, date);
            else tableWrapper = createNewTable(values, channelIdList, date);
        } else tableWrapper = createNewTable(values, channelIdList, date);

        // send table to OMERO
        try {
            tableWrapper.setName(tableName);
            this.imageWrapper.addTable(client, tableWrapper);
            IJLogger.info("Results table for image " + this.imageWrapper.getName() + " : " + this.imageWrapper.getId() + " has been uploaded");
        } catch (DSAccessException | ServiceException | ExecutionException e) {
            IJLogger.error("Cannot add the results table to image " + this.imageWrapper.getName() + " : " + this.imageWrapper.getId());
        }
    }

    @Override
    public void populateParentTable(Map<ImageWrapper, List<List<Double>>> summary, List<String> headers, boolean populateExistingTable) {
        // get the current date
        String date = Tools.getCurrentDateAndHour();

        // format data
        List<Object[]> fullRows = new ArrayList<>();
        for (Map.Entry<ImageWrapper, List<List<Double>>> image : summary.entrySet()) {
            List<List<Double>> allChannelMetrics = image.getValue();

            // convert to Object type
            List<List<Object>> allChannelMetricsAsObject = new ArrayList<>();
            for (List<Double> objects : allChannelMetrics)
                allChannelMetricsAsObject.add(new ArrayList<>(objects));

            for (List<Object> metrics : allChannelMetricsAsObject) {
                metrics.add(0, image.getKey().getName());
                metrics.add(0, image.getKey().asImageData());
                fullRows.add(metrics.toArray());
            }
        }

        try {
            // get the current dataset
            DatasetWrapper dataset = this.client.getDataset(Long.parseLong(this.datasetId));

            if(populateExistingTable) {
                // get existing table
                TableWrapper tableWrapper = getLastOmeroTable(client, dataset);

                // apply the adding/replacement policy
                if(tableWrapper == null)
                    addNewParentTable(fullRows, headers, dataset, date);
                else replaceExistingParentTable(fullRows, dataset, tableWrapper, date);
            } else
                addNewParentTable(fullRows, headers, dataset, date);

            IJLogger.info("Results table for dataset " + dataset.getName() + " : " + dataset.getId() + " has been uploaded");
        } catch (DSAccessException | ServiceException | ExecutionException e) {
            IJLogger.error("Cannot add the results table to dataset " + this.datasetId);
        }
    }

    private TableWrapper createNewTable(List<List<Double>> values, List<Integer> channelIdList, String date) {
        List<TableDataColumn> columns = new ArrayList<>();
        List<List<Object>> measurements = new ArrayList<>();
        int i = 0;

        if(values.size() > 0) {
            // add the first column with the image data (linkable on OMERO)
            columns.add(new TableDataColumn("Image ID", i++, ImageData.class));
            List<Object> imageData = new ArrayList<>();

            for (Double ignored : values.get(0)) {
                imageData.add(this.imageWrapper.asImageData());
            }
            measurements.add(imageData);

            // add the second column with the ring id (from top-left to bottom-right)
            columns.add(new TableDataColumn("Ring ID", i++, Long.class));
            List<Object> ids = new ArrayList<>();
            LongStream.range(0, values.get(0).size()).forEach(ids::add);
            measurements.add(ids);

            for (int j = 0; j < values.size(); j++) {
                columns.add(new TableDataColumn("ch" + channelIdList.get(j) + "_" + date, i++, Double.class));
                measurements.add(new ArrayList<>(values.get(j)));
            }
        }
        return new TableWrapper(new TableData(columns, measurements));
    }


    private TableWrapper addNewColumnsToTable(TableWrapper tableToPopulate, List<List<Double>> values, List<Integer> channelIdList, String date){
        TableData tableData = tableToPopulate.createTable();
        List<TableDataColumn> columns = new ArrayList<>(Arrays.asList(tableData.getColumns()));
        List<List<Object>> measurements = new ArrayList<>();

        Object[][] data = tableData.getData();
        for (Object[] datum : data) measurements.add(Arrays.asList(datum));
        int i = data.length;

        if(values.size() > 0) {
            for (int j = 0; j < values.size(); j++) {
                columns.add(new TableDataColumn("ch" + channelIdList.get(j) + "_" + date, i++, Double.class));
                measurements.add(new ArrayList<>(values.get(j)));
            }
        }
        return new TableWrapper(new TableData(columns, measurements));
    }


    private void addNewParentTable(List<Object[]> fullRows, List<String> headers, GenericRepositoryObjectWrapper<?> repo, String date) {
        try {
            // create a new table
            TableWrapper tableWrapper = new TableWrapper(headers.size() + 2, date + "_" + repo.getName() + "_Table");

            // set headers
            tableWrapper.setColumn(0, "Image", ImageData.class);
            tableWrapper.setColumn(1, "Label", String.class);

            int i = 2;
            for (String header : headers)
                tableWrapper.setColumn(i++, header, Double.class);

            // set table size
            tableWrapper.setRowCount(fullRows.size());

            // add metrics to the table
            for (Object[] row : fullRows) tableWrapper.addRow(row);

            // add the table to OMERO
            repo.addTable(client, tableWrapper);

            IJLogger.info("Results table for image " + this.imageWrapper.getName() + " : " + this.imageWrapper.getId() + " has been uploaded");
        } catch (DSAccessException | ServiceException | ExecutionException e) {
            IJLogger.error("Cannot add the results table to image " + this.imageWrapper.getName() + " : " + this.imageWrapper.getId());
        }
    }

    private void replaceExistingParentTable(List<Object[]> fullRows, GenericRepositoryObjectWrapper<?> repoWrapper, TableWrapper tableWrapper, String date){
        try {
            // get the table size
            int nExistingRows = tableWrapper.getRowCount();

            // set new table size
            tableWrapper.setRowCount(nExistingRows + fullRows.size());

            // add metrics to the table
            for (Object[] row : fullRows) tableWrapper.addRow(row);

            // duplicate the table
            TableWrapper newTable = new TableWrapper(tableWrapper.createTable());

            // set table name (with the new date)
            newTable.setName(date + "_" + repoWrapper.getName() + "_Table");

            // add the new table
            repoWrapper.addTable(client, newTable);

            // delete the previous table
            client.deleteFile(tableWrapper.getId());

            IJ.log("[INFO] [DataManagement][replaceTable] -- Table successfully updated for " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
        } catch (ServiceException | AccessException | ExecutionException e) {
            IJLogger.error("Cannot add results to previous table " + tableWrapper.getName() + " : " + tableWrapper.getId());
        } catch (OMEROServerError | InterruptedException e ){
            IJLogger.error("Cannot delete previous table " + tableWrapper.getName() + " : " + tableWrapper.getId());
        }
    }

    /**
     * save an image locally on the computer and returns the saved file
     *
     * @param imp
     * @param folder
     * @return
     */
    public static File saveHeatMapLocally(ImagePlus imp, String folder){
        FileSaver fs = new FileSaver(imp);
        // create an image file in the given folder, with the given imageName
        File analysisImage_output_path = new File(folder,imp.getTitle() + ".tif");

        // save the image
        boolean hasBeenSaved = fs.saveAsTiff(analysisImage_output_path.toString());

        // check if the image was correctly saved
       if(!hasBeenSaved)
           return null;
       return analysisImage_output_path;
    }

    private List<ShapeData> convertIJRoisToShapeData(List<Roi> rois, int channelId, String comment){
        List<ShapeData> shapes = new ArrayList<>();

        for(int i = 0; i< rois.size(); i++) {
            Roi roi = rois.get(i);
            int type = roi.getType();
            switch (type) {
                case Roi.OVAL:
                    EllipseData ellipse = new EllipseData(roi.getStatistics().xCentroid,
                            roi.getStatistics().yCentroid,
                            roi.getStatistics().roiWidth / 2,
                            roi.getStatistics().roiHeight / 2);
                    ellipse.setText(comment+":"+i+":child");
                    ellipse.setC(channelId);
                    shapes.add(ellipse);
                    break;
                case Roi.RECTANGLE:
                    // Build the OMERO object
                    RectangleData rectangle = new RectangleData(roi.getStatistics().roiX, roi.getStatistics().roiY, roi.getStatistics().roiWidth, roi.getStatistics().roiHeight);
                    // Write in comments the type of PathObject as well as the assigned class if there is one
                    rectangle.setText(comment+":"+i+":child");
                    // set the ROI position in the image
                    rectangle.setC(channelId);
                    shapes.add(rectangle);
            }
        }

        return shapes;
    }

    /**
     * get the last table attached to an image/dataset/project...
     *
     * @param client
     * @param repoWrapper
     * @return
     */
    private static TableWrapper getLastOmeroTable(Client client, GenericRepositoryObjectWrapper<?> repoWrapper) {
        try {
            // Prepare a Table
            List<TableWrapper> repoTables = repoWrapper.getTables(client);

            if (!repoTables.isEmpty()) {
                // get al names
                List<String> names = repoTables.stream().map(TableWrapper::getName).collect(Collectors.toList());

                // get dates
                List<String> orderedDate = new ArrayList<>();
                names.forEach(name-> orderedDate.add(name.split("_")[0]));

                // sort dates in reverse order (larger to smaller date)
                orderedDate.sort(Comparator.reverseOrder());

                // take the last table
                return repoTables.stream().filter(e->e.getName().contains(orderedDate.get(0))).collect(Collectors.toList()).get(0);
            } else
                return null;
        }catch(ServiceException | AccessException | ExecutionException e){
            IJLogger.error("Could not get tables attached to " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
            return null;
        }
    }

    private static TableWrapper getOmeroTable(Client client, GenericRepositoryObjectWrapper<?> repoWrapper, String tableName){
        try {
            List<TableWrapper> tables = repoWrapper.getTables(client).stream().filter(e -> e.getName().contains(tableName)).collect(Collectors.toList());
            if(!tables.isEmpty()) {
                TableWrapper table = tables.get(0);
                TableData tableData = client.getGateway().getFacility(TablesFacility.class).getTable(client.getCtx(), table.getFileId(), 0, table.getRowCount(),
                        IntStream.range(0, table.getColumnCount()).toArray());
                return new TableWrapper(tableData);
            }
           return null;
        }catch(ExecutionException | DSOutOfServiceException | DSAccessException e){
            IJLogger.error("Could not get table "+tableName+" attached to " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
            return null;
        }
    }
}
