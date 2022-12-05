package ch.epfl.biop;

import com.google.common.primitives.Doubles;
import fr.igred.omero.Client;
import fr.igred.omero.annotations.TableWrapper;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.ImageWrapper;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.*;
import ij.measure.CurveFitter;
import ij.measure.ResultsTable;
import ij.plugin.RoiEnlarger;
import ij.plugin.frame.RoiManager;
import ij.process.FloatProcessor;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import omero.gateway.model.ImageData;
import omero.model.NamedValue;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;


public class ImageProcessing {
    final private static int heatMapSize = 256;
    final public static String heatMapBitDepth = "32-bit black";
    final private static String thresholdingMethod = "Huang dark";

    final private static boolean measureGridStep = true;
    final private static String imageCenter = "cross";

    final private static int argoSpacing = 5; // um
    final private static int argoFOV = 100; // um
    final private static int argoNPoints = 21; // on each row/column


    /**
     *  run analysis over all images within the dataset and create dataset measurements table.
     *  Dataset table can also be saved locally if specified.
     *
     * @param client
     * @param imageWrapperList
     * @param datasetWrapper
     * @param testedMicroscope
     * @param savingOption
     * @param folder
     * @param processAllImages
     * @param saveResultsLocally
     */
    public static void processDataset(Client client,
                                      List<ImageWrapper> imageWrapperList,
                                      DatasetWrapper datasetWrapper,
                                      String testedMicroscope,
                                      String savingOption,
                                      File folder,
                                      boolean processAllImages,
                                      boolean saveResultsLocally) {
        // get the current date
        LocalDateTime localDateTime = LocalDateTime.now();
        LocalTime localTime = localDateTime.toLocalTime();
        LocalDate localDate = localDateTime.toLocalDate();

        String date = ""+localDate.getYear()+
                localDate.getMonthValue()+
                localDate.getDayOfMonth()+"-"+
                localTime.getHour()+"h"+
                localTime.getMinute() +"m"+
                localTime.getSecond();

        // get the name of the tables
        String tableName = date+"_"+testedMicroscope+"_Table";

        // get the latest OMERO table if exists
        TableWrapper tableWrapper = DataManagement.getLastOmeroTable(client, datasetWrapper);
        if(processAllImages)
            tableWrapper = null;

        List<ImageData> firstTableColumn = new ArrayList<>();

        // process each image that have not been processed yet
        for(ImageWrapper imageWrapper:imageWrapperList) {
            tableWrapper = processImage(client,
                    imageWrapper,
                    tableWrapper,
                    firstTableColumn,
                    datasetWrapper.getName(),
                    datasetWrapper.getId(),
                    tableName,
                    testedMicroscope,
                    savingOption,
                    folder,
                    processAllImages,
                    saveResultsLocally);
        }

        if(!saveResultsLocally)
            // add the ResultsTable on OMERO
            DataManagement.sendDatasetTableToOmero(client,
                    datasetWrapper,
                    tableWrapper,
                    tableName,
                    folder.getAbsolutePath(),
                    processAllImages);
        else {
            try {
                // get the latest local csv table if exists
                File previousTable = null;
                if (!processAllImages) {
                    previousTable = DataManagement.getLastLocalTable(folder, testedMicroscope);
                }

                // save the current table as csv in the specified folder
                String path = folder.getAbsolutePath() + File.separator + tableName + ".csv";
                tableWrapper.saveAs(path, ',');

                if (!processAllImages && previousTable != null) {
                    // combine previous existing table with new results
                    ResultsTable previousLocalTable = DataManagement.readCsvTableAsResultsTable(previousTable);
                    ResultsTable currentLocalTable = DataManagement.readCsvTableAsResultsTable(new File(path));
                    ResultsTable newTable = DataManagement.concatenateResultsTables(previousLocalTable, currentLocalTable);
                    newTable.save(path);
                    // delete the previous csv table
                    previousTable.delete();
                }
            } catch(Exception e){
                IJ.log("[ERROR] [processDataset] -- Error when saving the tableWrapper as csv table in "+folder.getAbsolutePath() + File.separator +  tableName + ".csv");
            }
        }
    }


    /**
     * compute the full analysis pipeline on an image and save metrics/heat maps on OMERO
     *
     * @param client
     * @param imageWrapper
     * @param tableWrapper
     * @param firstTableColumn
     * @param datasetName
     * @param datasetId
     * @param tableName
     * @param processAllImages
     * @param testedMicroscope
     * @param savingOption
     * @param folder
     */
    public static TableWrapper processImage(Client client,
                                    ImageWrapper imageWrapper,
                                    TableWrapper tableWrapper,
                                    List<ImageData> firstTableColumn,
                                    String datasetName,
                                    long datasetId,
                                    String tableName,
                                    String testedMicroscope,
                                    String savingOption,
                                    File folder,
                                    boolean processAllImages,
                                    boolean saveResultsLocally) {

        // pixel size of the image
        final double pixelSizeImage = imageWrapper.getPixels().getPixelSizeX().getValue();
        // spot radius to compute the FWHM
        final double lineLength = (int)(1.25 / pixelSizeImage);
        // spot radius to compute other metrics
        final double ovalRadius = (int)(1.25 / pixelSizeImage);
        // Number of channels
        final int NChannels = imageWrapper.getPixels().getSizeC();

        // get the image name without the extension
        final String rawImageName = DataManagement.getNameWithoutExtension(imageWrapper.getName());

        IJ.log("[INFO] [runAnalysis] -- Running on image " +imageWrapper.getId());
        IJ.log("[INFO] [runAnalysis] -- Pixel size : "+pixelSizeImage+" um");

        // initialize variables
        ResultsTable analysisResultsRT = new ResultsTable();
        ResultsTable pccResultsRT = new ResultsTable();
        RoiManager roiManager = new RoiManager();

        List<Roi> generalRoisForPCC = new ArrayList<>();
        List<ImagePlus> distortionMaps = new ArrayList<>();
        List<ImagePlus> uniformityMaps = new ArrayList<>();
        List<ImagePlus> fwhmMaps = new ArrayList<>();
        List<ImagePlus> pccMaps = new ArrayList<>();

        Map<String, String> processingParameters = new HashMap<>();
        processingParameters.put("Pixel_size_(um)",""+pixelSizeImage);
        processingParameters.put("Profile_length_for_FWHM_(pix)",""+lineLength);
        processingParameters.put("Oval_radius_(pix)",""+ovalRadius);
        processingParameters.put("thresholding_method", thresholdingMethod);

        // open the image on ImageJ
        ImagePlus imp;
        try {
           imp = imageWrapper.toImagePlus(client);
        }catch(AccessException | ServiceException | ExecutionException e){
            throw new RuntimeException(e);
        }

        for(int i = 0; i < NChannels; i++){
            // reset all windows
            IJ.run("Close All", "");
            roiManager.reset();
            IJ.log("[INFO] [runAnalysis] -- Running on channel " +i);

            // add image name and channel id to the table
            analysisResultsRT.incrementCounter();
            int lastRow = analysisResultsRT.getCounter()-1;
            analysisResultsRT.setValue("Image name", lastRow, rawImageName);
            analysisResultsRT.setValue("Channel", lastRow, i);
            analysisResultsRT.setValue("Acquisition date", lastRow, DataManagement.getAcquisitionDate(imageWrapper));
            firstTableColumn.add(imageWrapper.asImageData());

            // extract the current channel
            ImagePlus channel = IJ.createHyperStack(imp.getTitle() + "_ch" + i, imp.getWidth(), imp.getHeight(), 1, 1, 1, imp.getBitDepth());
            imp.setPosition(i+1,1,1);
            channel.setProcessor(imp.getProcessor());
            channel.show();

            // get the central cross
            Roi crossRoi = getCentralCross(channel, roiManager, pixelSizeImage);
            double xCross = crossRoi.getStatistics().xCentroid;
            double yCross = crossRoi.getStatistics().yCentroid;
            IJ.log("[INFO] [runAnalysis] -- Cross = " +crossRoi);

            // add the cross ROI on the image
            roiManager.addRoi(crossRoi);
            roiManager.addRoi(new PointRoi(xCross,yCross));
            channel.setRoi(crossRoi);

            // create a difference of gaussian image to find point centers
            double sigma = 0.14 * argoSpacing / pixelSizeImage; // was 0.3 * at the beginning but the detected center was a bit eccentered from the real center
            //double sigma2 = sigma1 / Math.sqrt(2);
            ImagePlus dogImage = dogFilter(imp, sigma);
            processingParameters.put("Gaussian_filtering_sigma_(pix)",""+sigma);
            IJ.log("[INFO] [runAnalysis] -- Gaussian filtering sigma = " +sigma + " pix");

            // get the coordinates of each ring
            List<Point2D> gridPoints = getGridPoint(dogImage, crossRoi, pixelSizeImage);

            // reduced grid to compute average step
            List<Point2D> smallerGrid = gridPoints.stream().filter(e -> (Math.abs(e.getX() - xCross) < 12.5 / pixelSizeImage && Math.abs(e.getY() - yCross) < 12.5 / pixelSizeImage)).collect(Collectors.toList());

            // get the average x step
            double xStepAvg = getAverageStep(smallerGrid.stream().map(Point2D::getX).collect(Collectors.toList()), pixelSizeImage);
            processingParameters.put("xStepAvg_(pix)",""+xStepAvg);
            IJ.log("[INFO] [runAnalysis] -- xStepAvg = " +xStepAvg + " pix");

            // get the average y step
            double yStepAvg = getAverageStep(smallerGrid.stream().map(Point2D::getY).collect(Collectors.toList()), pixelSizeImage);
            processingParameters.put("yStepAvg_(pix)",""+yStepAvg);
            IJ.log("[INFO] [runAnalysis] -- yStepAvg = " +yStepAvg + " pix");

            // get the rotation angle
            double rotationAngle = getRotationAngle(gridPoints, crossRoi);
            analysisResultsRT.setValue("Rotation angle deg", lastRow, rotationAngle*180/Math.PI);
            IJ.log("[INFO] [runAnalysis] -- Rotation angle theta = "+rotationAngle*180/Math.PI + "°");

            // get the ideal grid
            List<Point2D> idealGridPoints = getIdealGridPoints(crossRoi, (int)Math.sqrt(gridPoints.size() + 1), xStepAvg, yStepAvg, rotationAngle);
            // display all grid points
            gridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi(pR.getX()-1.2*ovalRadius, pR.getY()-1.2*ovalRadius, 2.4*ovalRadius, 2.4*ovalRadius));});

            // sort the computed grid points according to ideal grid order
            gridPoints = sortFromReference(Arrays.asList(roiManager.getRoisAsArray()), idealGridPoints);

            // display ideal grid points
            roiManager.reset();
            gridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi(pR.getX()-ovalRadius, pR.getY()-ovalRadius, 2*ovalRadius, 2*ovalRadius));});
            generalRoisForPCC = Arrays.asList(roiManager.getRoisAsArray());
            gridPoints.forEach(pR-> {roiManager.addRoi(new PointRoi(pR.getX(), pR.getY()));});
            idealGridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi(pR.getX()-ovalRadius/2, pR.getY()-ovalRadius/2, ovalRadius, ovalRadius));});
            roiManager.runCommand(channel,"Show All without labels");

            // compute metrics
            List<Double> fieldDistortionValues = computeFieldDistortion(gridPoints, idealGridPoints, pixelSizeImage);
            List<Double> fieldUniformityValues = computeFieldUniformity(gridPoints,channel,ovalRadius);
            List<Double> fwhmValues = computeFWHM(gridPoints,channel, lineLength, roiManager, pixelSizeImage);
            List<Double> crossPositionOnImage = Doubles.asList(xCross, imp.getWidth() - xCross, yCross, imp.getHeight() - yCross);
            crossPositionOnImage.forEach(e->e = e*pixelSizeImage);

            // compute statistics on each metrics and save them in the resultsTable
            IJ.log("[INFO] [runAnalysis] -- compute field distortion");
            computeStatistics(fieldDistortionValues, analysisResultsRT,"field_distortion", lastRow, "um");
            IJ.log("[INFO] [runAnalysis] -- compute field uniformity");
            computeStatistics(fieldUniformityValues,analysisResultsRT,"field_uniformity", lastRow, "");
            IJ.log("[INFO] [runAnalysis] -- compute FWHM");
            computeStatistics(fwhmValues, analysisResultsRT,"fwhm",lastRow, "um");
            IJ.log("[INFO] [runAnalysis] -- compute pattern centering");
            computeStatistics(crossPositionOnImage, analysisResultsRT,"cross_center_to_image_borders", lastRow, "um");

            // build heat maps of each metrics
            distortionMaps.add(computeHeatMap("field_distortion", fieldDistortionValues, (int) Math.sqrt(gridPoints.size() + 1)));
            uniformityMaps.add(computeHeatMap("field_uniformity", fieldUniformityValues, (int) Math.sqrt(gridPoints.size() + 1)));
            fwhmMaps.add(computeHeatMap("fwhm", fwhmValues, (int) Math.sqrt(gridPoints.size() + 1)));
        }

        // compute PCC if there is more than 1 channel
        if(NChannels > 1){
            // create a new resultsTable
            pccResultsRT.incrementCounter();

            int cnt = 0;
            for(int i = 0; i < NChannels-1; i++){
                for(int j = i+1; j < NChannels; j++){
                    // get the two images
                    imp.setPosition(i+1,1,1);
                    ImagePlus ch1 = new ImagePlus("ch"+i, imp.getProcessor());
                    imp.setPosition(j+1,1,1);
                    ImagePlus ch2 = new ImagePlus("ch"+j, imp.getProcessor());

                    // compute Pearson Correlation Coefficient
                    List<Double> pccValues = computePCC(ch1, ch2, generalRoisForPCC);
                    // compute PCC statistics
                    IJ.log("[INFO] [runAnalysis] -- compute PCC for ch"+i+" - ch"+j);
                    computeStatistics(pccValues, pccResultsRT, "PCC_(ch"+i+"-ch"+j+")", cnt, "");
                    // compute PCC heat map
                    pccMaps.add(computeHeatMap("PCC_(ch"+i+"-ch"+j+")", pccValues, (int) Math.sqrt(pccValues.size() + 1)));
                    cnt++;
                }
            }
        }

        // add tags to the image on OMERO
        DataManagement.addTag(client, imageWrapper,"raw");
        DataManagement.addTag(client, imageWrapper,"argolight");

        if(!saveResultsLocally) {
            // create project's key value pairs
            List<NamedValue> keyValues = DataManagement.generateKeyValuesForProject(client, imageWrapper, datasetName, testedMicroscope, processingParameters);
            // add the key values on OMERO
            DataManagement.addKeyValues(client, imageWrapper, keyValues);
        }

        // populate dataset table
        tableWrapper = DataManagement.populateDatasetTable(tableWrapper, client, analysisResultsRT, imageWrapper.getId());

        // add the PCC ResultsTable on OMERO
        if(NChannels > 1 && !saveResultsLocally)
            DataManagement.sendImageTableToOmero(client, imageWrapper, imageWrapper.getId(), pccResultsRT,tableName+"_PCC", folder.getAbsolutePath(), processAllImages);

        if(NChannels > 1 && saveResultsLocally){
            File localTableFile = new File(folder, tableName.replace(" ","_") + "_PCC.csv");
            pccResultsRT.save(localTableFile.toString());
        }

        // save heat maps on the computer
        if(savingOption.equals("Save heat maps locally")){
            DataManagement.saveHeatMapsLocally(distortionMaps,rawImageName,folder.getAbsolutePath());
            DataManagement.saveHeatMapsLocally(uniformityMaps,rawImageName,folder.getAbsolutePath());
            DataManagement.saveHeatMapsLocally(fwhmMaps,rawImageName,folder.getAbsolutePath());
            if(NChannels > 1)
                DataManagement.saveHeatMapsLocally(pccMaps,rawImageName,folder.getAbsolutePath());
        }

        // save heat maps on OMERO
        if(savingOption.equals("Save heat maps in OMERO")){
            DataManagement.uploadHeatMaps(client,datasetId,distortionMaps,rawImageName,folder.getAbsolutePath());
            DataManagement.uploadHeatMaps(client,datasetId,uniformityMaps,rawImageName,folder.getAbsolutePath());
            DataManagement.uploadHeatMaps(client,datasetId,fwhmMaps,rawImageName,folder.getAbsolutePath());
            if(NChannels > 1)
                DataManagement.uploadHeatMaps(client,datasetId,pccMaps,rawImageName,folder.getAbsolutePath());
        }

        roiManager.close();
        return tableWrapper;
    }


    /**
     * find the central cross of the ArgoLight pattern B
     *
     * @param imp
     * @param rm
     * @return
     */
    private static Roi getCentralCross(ImagePlus imp, RoiManager rm, double imagePixelSize){
        rm.reset();

        // make sure no ROI is left on the imp
        IJ.run(imp, "Select None", "");
        IJ.run("Remove Overlay", "");

        // Detect Cross in the center of the FOV
        ImagePlus mask_imp = imp.duplicate();
        IJ.setAutoThreshold(mask_imp, thresholdingMethod);
        IJ.run(mask_imp, "Convert to Mask", "");
        IJ.run(mask_imp, "Analyze Particles...", "size="+(2.5/imagePixelSize)+"-Infinity add"); // use pixel size dependency instead of 0.04*imp.getWidth()

        // get central ROIs while excluding bounding semi-crosses
        double gridFactor = argoFOV/(4*imagePixelSize); // size of the central window depend on the pixel size
        List<Roi> rois = Arrays.stream(rm.getRoisAsArray()).filter(roi -> ((roi.getStatistics().xCentroid < imp.getWidth()/2.0 + gridFactor
                && roi.getStatistics().xCentroid > imp.getWidth()/2.0 - gridFactor)
                && (roi.getStatistics().yCentroid < imp.getHeight()/2.0 + gridFactor
                && roi.getStatistics().yCentroid > imp.getHeight()/2.0 - gridFactor))).collect(Collectors.toList());

        // get the ROI with larger width corresponding to the central cross
        Roi crossRoi = rois.stream().max(Comparator.comparing(roi -> roi.getStatistics().roiWidth)).get();

        rm.reset();
        return new Roi(crossRoi.getBounds());
    }


    /**
     * gaussian blur the image
     *
     * @param imp
     * @param sigma
     * @return
     */
    private static ImagePlus dogFilter(ImagePlus imp, double sigma){
        ImagePlus impGauss1 = imp.duplicate();
        IJ.run(impGauss1, "32-bit", "");
        IJ.run(impGauss1, "Gaussian Blur...", "sigma="+sigma);

        return impGauss1;
    }

    /**
     * generate a list of point with small rings coordinates.
     *
     * @param DoGImage
     * @param crossRoi
     * @param pixelSizeImage
     * @return
     */
    private static List<Point2D> getGridPoint(ImagePlus DoGImage, Roi crossRoi, double pixelSizeImage){
        int nPoints;
        Roi enlargedRectangle;

        // compute the number of points on each side of the center to adapt the size of the large rectangle
        // if the FoV of the image is smaller than the pattern FoV => limited number of points
        if((DoGImage.getWidth()*pixelSizeImage < (argoFOV + 4)) && (DoGImage.getHeight()*pixelSizeImage < (argoFOV + 4))){  // 100um of field of view + 2 um on each side
            nPoints = (int)Math.min(Math.floor(((DoGImage.getWidth()*pixelSizeImage/2)-2 )/argoSpacing), Math.floor(((DoGImage.getHeight()*pixelSizeImage/2)-2)/argoSpacing));
            enlargedRectangle = RoiEnlarger.enlarge(crossRoi, (int)Math.round(((nPoints*argoSpacing+2.5)/(crossRoi.getStatistics().roiWidth*pixelSizeImage/2))*crossRoi.getStatistics().roiWidth/2));

        }
        else{
            // for image FoV larger than the pattern FoV => all points
            nPoints = (argoNPoints-1)/2;
            enlargedRectangle = RoiEnlarger.enlarge(crossRoi, (int)Math.round(((nPoints*argoSpacing+1.5)/(crossRoi.getStatistics().roiWidth*pixelSizeImage/2))*crossRoi.getStatistics().roiWidth/2));
        }

        // get the statistics
        double large_rect_roi_x = enlargedRectangle.getStatistics().xCentroid;
        double large_rect_roi_y = enlargedRectangle.getStatistics().yCentroid;
        double large_rect_roi_w = enlargedRectangle.getStatistics().roiWidth;
        double large_rect_roi_h = enlargedRectangle.getStatistics().roiHeight;

        // find ring centers
        // add prominence 1, exclude points on image edge and restrict points to be closed (not open on an edge)
        IJ.run(DoGImage, "Find Maxima...", "prominence=1 strict exclude output=[List]");
        ResultsTable rt_points = ResultsTable.getResultsTable("Results");

        if(rt_points == null)
           return new ArrayList<>();

        // get coordinates of each point
        float[] raw_x_array = rt_points.getColumn(0);
        float[] raw_y_array = rt_points.getColumn(1);

        List<Point2D> gridPoints = new ArrayList<>();

        // filter points according to their position ; keep only those inside the large rectangle and outside the central cross bounding box
        for(int i = 0; i < raw_x_array.length; i++){

            if(Math.abs(raw_x_array[i] - large_rect_roi_x) <= large_rect_roi_w/2 &&
                    Math.abs(raw_y_array[i] - large_rect_roi_y) <= large_rect_roi_h/2 &&
                    !(Math.abs(raw_x_array[i] - crossRoi.getStatistics().xCentroid) <= crossRoi.getStatistics().roiWidth/2 &&
                            Math.abs(raw_y_array[i] - crossRoi.getStatistics().yCentroid) <= crossRoi.getStatistics().roiHeight/2)){
                gridPoints.add(new Point2D.Double(raw_x_array[i], raw_y_array[i]));
            }
        }

        return gridPoints;
    }


    /**
     * compute the average step between values of the list
     *
     * The number of points should correspond to a square number - 1, like the below pattern.
     *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *    * * * *   * * * *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *    * * * * * * * * *
     *
     * @param values
     * @param pixelSize
     * @return
     */
    private static double getAverageStep(List<Double> values, double pixelSize){
        // get min/max values
        double min = values.stream().min(Comparator.naturalOrder()).get();
        double max = values.stream().max(Comparator.naturalOrder()).get();

        // compute the raw step
        double step_calc = (max - min) / (int)Math.sqrt(values.size());

        // tolerance on ring center to by-pass potential image rotation
        int tol = (int)(0.6 * argoSpacing / pixelSize);

        // average values on the same line
        List<Double> linesAvgs = new ArrayList<>();
        IntStream.range(0, (int)Math.sqrt(values.size()+1)).forEach(line_idx -> {
            // get all values on the same line
            List<Double> lines = values.stream().filter(e -> ((e > min + line_idx * step_calc - tol) && (e < min + line_idx * step_calc + tol))).collect(Collectors.toList());

            // average all values
            linesAvgs.add(lines.stream().reduce(0.0, Double::sum) / lines.size());
        });

        // compute step between each line
        List<Double> stepAvgs = new ArrayList<>();
        IntStream.range(0, linesAvgs.size()-2).forEach(line_idx -> {
            stepAvgs.add(linesAvgs.get(line_idx+1) - linesAvgs.get(line_idx));
        });

        // compute the average step
        return  stepAvgs.stream().reduce(0.0, Double::sum) / stepAvgs.size();
    }

    /**
     * compute the rotation angle of a point pattern
     *
     * @param values
     * @param crossRoi
     * @return
     */
    private static double getRotationAngle(List<Point2D> values, Roi crossRoi){
        double xCross = crossRoi.getStatistics().xCentroid;
        double yCross = crossRoi.getStatistics().yCentroid;

        // sort all points in natural order of their distance to image center
        List<Point2D> sortedPoints = values.stream().sorted(Comparator.comparing(e->e.distance(xCross,yCross))).collect(Collectors.toList());

        // extract corners
        int lastIdx = values.size()-1;
        List<Point2D> cornerPoints = Arrays.asList(sortedPoints.get(lastIdx), sortedPoints.get(lastIdx - 1), sortedPoints.get(lastIdx - 2), sortedPoints.get(lastIdx - 3));

        // get the top left and top right corners
        Point2D topLeftCorner = cornerPoints.stream().filter(e->e.getX()<xCross && e.getY()<yCross).collect(Collectors.toList()).get(0);
        Point2D topRightCorner = cornerPoints.stream().filter(e->e.getX()>xCross && e.getY()<yCross).collect(Collectors.toList()).get(0);

        // compute the rotation angle
        double theta = 0;
        if(Math.abs(topRightCorner.getX() - topLeftCorner.getX()) > 0.01){
            theta = Math.atan2(topRightCorner.getY() - topLeftCorner.getY(),topRightCorner.getX() - topLeftCorner.getX());
        }
        return theta;
    }


    /**
     * generate an ideal grid of points, based on the computed spacing between rings and the on the rotation angle
     *
     * @param crossRoi
     * @param nPoints
     * @param xStepAvg
     * @param yStepAvg
     * @param theta
     * @return
     */
    private static List<Point2D> getIdealGridPoints(Roi crossRoi, int nPoints, double xStepAvg, double yStepAvg, double theta){
        // get central cross position
        double xCross = crossRoi.getStatistics().xCentroid;
        double yCross = crossRoi.getStatistics().yCentroid;

        // compute the affine transform based on the rotation angle
        AffineTransform at = AffineTransform.getRotateInstance(theta, xCross, yCross);

        List<Point2D> idealPoints = new ArrayList<>();

        // define the ideal grid of dots
        IntStream.range(-(nPoints-1)/2, (nPoints-1)/2+1).forEach(yP -> {
            IntStream.range(-(nPoints-1)/2, (nPoints-1)/2+1).forEach(xP -> {
                if (!( xP == 0 && yP == 0) ){ // to avoid making a point at the cross
                    double[] pt = {xCross + xP * xStepAvg, yCross + yP * yStepAvg};
                    at.transform(pt, 0, pt, 0, 1);
                    idealPoints.add(new Point2D.Double(pt[0], pt[1]));
                }
            });
        });

       return idealPoints;
    }


    /**
     * sort a list of ROIs in the same order as the reference list
     *
     * Here we check if "ideal" point from the grid are contained in one of the ROI of the rings,
     * So we have both list in the same order!
     *
     * @param listToSort
     * @param reference
     * @return
     */
    private static List<Point2D> sortFromReference(List<Roi> listToSort, List<Point2D> reference){
        List<Point2D> sorted = new ArrayList<>();

        // sort the list
        reference.forEach( iPt ->{
            for(Roi gPt : listToSort){
                if (gPt.contains((int)iPt.getX(),(int)iPt.getY())) {
                    sorted.add(new Point2D.Double(gPt.getStatistics().xCentroid, gPt.getStatistics().yCentroid));
                    break;
                }
            }
        });

        return sorted;
    }


    /**
     * compute field distortion metric between an ideal and real set of points in um.
     *
     * @param gridPoints
     * @param idealGridPoints
     * @return
     */
    private static List<Double> computeFieldDistortion(List<Point2D> gridPoints, List<Point2D> idealGridPoints, double pixelSize){
        // Now we measure distance between ideal point and the measured one
        List<Double> distValues = new ArrayList<>();
        for(int i = 0; i < gridPoints.size(); i++){
            distValues.add(gridPoints.get(i).distance(idealGridPoints.get(i))*pixelSize);
        }

        return distValues;
    }

    /**
     * compute field uniformity metric of points set.
     *
     * @param gridPoints
     * @param imp
     * @param ovalRadius
     * @return
     */
    private static List<Double> computeFieldUniformity(List<Point2D> gridPoints, ImagePlus imp, double ovalRadius){
        List<Double> intensityValues = new ArrayList<>();
        gridPoints.forEach(pt->{
            // set the ROI & make a circle
            OvalRoi ovalRoi = new OvalRoi(pt.getX()-ovalRadius, pt.getY()-ovalRadius, 2*ovalRadius, 2*ovalRadius);
            imp.setRoi(ovalRoi);
            // compute the mean intensity of the ROI
            intensityValues.add(imp.getStatistics().mean);
        });

        return intensityValues;
    }

    /**
     * compute the Full Width at Half Maximum of a ring in um.
     *
     * @param gridPoints
     * @param imp
     * @param lineLength
     * @param rm
     * @return
     */
    private static List<Double> computeFWHM(List<Point2D> gridPoints, ImagePlus imp, double lineLength, RoiManager rm, double pixelSize){
        List<Double> fwhmValues = new ArrayList<>();

        // for each ring
        gridPoints.forEach(pt->{
            // set the ROI & draw a vertical line from the ring center
            Line line_roi = new Line(pt.getX(), pt.getY(), pt.getX(), pt.getY() - lineLength);
            rm.addRoi(line_roi);

            // measure pixel intensity values using ProfilePlot ,yData, and creates an xData from calibration
            imp.deleteRoi();
            imp.setRoi(line_roi);
            ProfilePlot pfpt = new ProfilePlot(imp);
            double[] yData = pfpt.getProfile();

            // TODO make a better xData, foresee issue with tilted line
            double[] xData = IntStream.range(0, yData.length).asDoubleStream().toArray();

            // DO the curve fitting
            CurveFitter crvFitr = new CurveFitter(xData, yData);
            crvFitr.doFit(CurveFitter.GAUSSIAN);
            double[] params = crvFitr.getParams();

            // Fit parameters as follows: y = a + (b - a) exp( -(x - c^2)/(2*d^2) )
            double d = params[3]; // parameter d of gaussian, related to the FWHM, see http://fr.wikipedia.org/wiki/Largeur_%C3%A0_mi-hauteur
            double fwhm_val = (2 * Math.sqrt(2 * Math.log(2))) * d;

            fwhmValues.add(fwhm_val*pixelSize);
        });

        return fwhmValues;
    }


    /**
     * compute the Pearson Correlation Coefficient between two images, given a list of regions where to compute it.
     *
     * @param imp1
     * @param imp2
     * @param rois
     * @return
     */
    private static List<Double> computePCC(ImagePlus imp1, ImagePlus imp2, List<Roi> rois){
        List<ImagePlus> imp1Crops = new ArrayList<>();
        List<ImagePlus> imp2Crops = new ArrayList<>();

        // crop images with ROIs' bounding boxes
        rois.forEach(roi->{
            imp1.setRoi(roi);
            imp1Crops.add(imp1.crop());
            imp2.setRoi(roi);
            imp2Crops.add(imp2.crop());
        });

        return computePCC(imp1Crops, imp2Crops);
    }

    /**
     * compute the Pearson Correlation Coefficient between two lists of images.
     * One PCC is computed for each pair of images ==> lists must have the same length.
     *
     * @param impList1
     * @param impList2
     * @return
     */
    private static List<Double> computePCC(List<ImagePlus> impList1, List<ImagePlus> impList2){
        // check lists' length
        if(impList1.size() != impList2.size()) {
            IJ.log("[ERROR] [computePCC] -- Images lists do not have the same size :  "+impList1.size()+" vs " +impList2.size());
            return new ArrayList<>();
        }

        // for each patch, compute PCC
        List<Double> pccList = new ArrayList<>();
        for(int i = 0; i < impList1.size(); i++)
            pccList.add(computePCC(impList1.get(i),impList2.get(i)));

        return pccList;
    }


    /**
     * compute the Pearson Correlation Coefficient between two images.
     * Images must have the same dimensions.
     *
     * @param imp1
     * @param imp2
     * @return
     */
    private static double computePCC(ImagePlus imp1, ImagePlus imp2){
        // check image dimensions
        if(imp1.getWidth() != imp2.getWidth() || imp1.getHeight() != imp2.getHeight()) {
            IJ.log("[ERROR] [computePCC] -- Image patches do not have the same dimensions ; w x h : "+imp1.getWidth()+" x " +imp1.getHeight() +" and " + imp2.getWidth()+" x "+imp2.getHeight());
            return Double.NaN;
        }

        List<Float> array1 = new ArrayList<>();
        List<Float> array2 = new ArrayList<>();

        // get raw pixel values
        for(int k = 0; k < imp1.getWidth(); k++){
            for (int l = 0; l < imp1.getHeight(); l++){
                array1.add(imp1.getProcessor().getPixelValue(k, l));
                array2.add(imp2.getProcessor().getPixelValue(k, l));
            }
        }

        // compute PCC
        PearsonsCorrelation pcc = new PearsonsCorrelation();
        return pcc.correlation(array1.stream().mapToDouble(Float::floatValue).toArray(), array2.stream().mapToDouble(Float::floatValue).toArray());
    }

    /**
     * compute basic statistics on a list of values (average, std, min, max) and store results in the given ResultsTable
     *
     * @param values
     * @param rt
     * @param metric
     * @param channel
     */
    private static void computeStatistics(List<Double> values, ResultsTable rt, String metric, int channel, String unit){
        // average value
        double average = values.stream().reduce(0.0, Double::sum) / values.size();

        // std value
        List<Double> stdList = new ArrayList<>();
        values.forEach(e->stdList.add(Math.pow(e - average,2)));
        double std = Math.sqrt(stdList.stream().reduce(0.0, Double::sum)/values.size());

        // min value
        double min = values.stream().min(Comparator.naturalOrder()).get();
        // max value
        double max = values.stream().max(Comparator.naturalOrder()).get();

        // save statistics
        rt.setValue(metric+"_avg "+unit, channel, average);
        rt.setValue(metric+"_std "+unit, channel, std);
        rt.setValue(metric+"_min "+unit, channel, min);
        rt.setValue(metric+"_max "+unit, channel, max);

        IJ.log("[INFO] [computeStatistics] -- average : " +average +" "+ unit);
        IJ.log("[INFO] [computeStatistics] -- std : " +std +" "+ unit);
        IJ.log("[INFO] [computeStatistics] -- min : " +min +" "+ unit);
        IJ.log("[INFO] [computeStatistics] -- max : " +max +" "+ unit);
    }

    /**
     * generate a heat map of the values list.
     * Each bloc of the heat map correspond to one ring of the ArgoLight pattern
     *
     * @param title
     * @param values
     * @param nPoints
     * @return
     */
    private static ImagePlus computeHeatMap(String title, List<Double> values, int nPoints){

        ImagePlus imp = IJ.createImage(title, heatMapBitDepth, nPoints, nPoints, 1);

        // set to each pixel the value for one ring
        values.add((int)Math.floor(values.size()/2.0), Double.NaN); // here we have a O in the center, because we didn't measure anything there
        FloatProcessor fp = new FloatProcessor(nPoints, nPoints, values.stream().mapToDouble(Double::doubleValue).toArray());
        imp.getProcessor().setPixels(1, fp);

        // enlarged the heat map to have a decent image size at the end
        ImagePlus enlarged_imp = imp.resize(heatMapSize, heatMapSize, "none");
        enlarged_imp.setTitle(title);

        // color the heat map with a lookup table
        IJ.run(enlarged_imp, "Fire", "");
        enlarged_imp.show();

        return enlarged_imp;
    }
}
