package ch.epfl.biop;

import fr.igred.omero.Client;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.ImageWrapper;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.*;
import ij.measure.CurveFitter;
import ij.measure.ResultsTable;
import ij.plugin.ImageCalculator;
import ij.plugin.RoiEnlarger;
import ij.plugin.frame.RoiManager;
import ij.process.FloatProcessor;

import java.awt.Image;
import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class ImageProcessing {

    final private static int heatMapSize = 256;
    final private static String heatMapBitDepth = "32-bit black";
    final private static String thresholdingMethod = "Huang dark";

    final private static boolean measureGridStep = true;
    final private static String imageCenter = "cross";
    //final private static int ovalDiameter = 20;

    final private static int argoSpacing = 5; // um
    final private static int argoFOV = 100; // um
    final private static int argoNPoints = 21; // on each row/column

    public static void runAnalysis(Client client, ImageWrapper imageWrapper, DatasetWrapper datasetWrapper, String argoLightName) {

        final double pixelSizeImage = imageWrapper.getPixels().getPixelSizeX().getValue();
        final double lineLength = (int)(1.25 / pixelSizeImage);
        final double ovalRadius = (int)(1.25 / pixelSizeImage);
        final int NChannels = imageWrapper.getPixels().getSizeC();

        System.out.println("Pixel size : "+pixelSizeImage);

        String rawImageName = DataManagement.getNameWithoutExtension(imageWrapper.getName());

        ResultsTable analysisResultsRT = new ResultsTable();
        List<ImagePlus> distortionMaps;
        List<ImagePlus> uniformityMaps;
        List<ImagePlus> fwhmMaps;

        // open the image on ImageJ
        ImagePlus imp;
        try {
           imp = imageWrapper.toImagePlus(client);
        }catch(AccessException | ServiceException | ExecutionException e){
            throw new RuntimeException(e);
        }

        for(int i = 0; i < NChannels; i++){
            IJ.run("Close All", "");

            // extract the current channel
            ImagePlus channel = IJ.createHyperStack(imp.getTitle() + "_ch" + (i + 1), imp.getWidth(), imp.getHeight(), 1, 1, 1, imp.getBitDepth());
            imp.setPosition(i+1,1,1);
            channel.setProcessor(imp.getProcessor());
            channel.show();

            // get the central cross
            Roi crossRoi = getCenterCross(channel);
            RoiManager roiManager = new RoiManager();
            roiManager.addRoi(crossRoi);
            roiManager.addRoi(new PointRoi(crossRoi.getStatistics().xCentroid,crossRoi.getStatistics().yCentroid));
            channel.setRoi(crossRoi);

            // create a difference of gaussian image to find point centers
            double sigma1 = 0.1 * argoSpacing / pixelSizeImage; // was 0.3 * at the begining but the detected center was a bit eccentered from the real center
            double sigma2 = sigma1 / Math.sqrt(2);
            ImagePlus dogImage = DoGFilter(imp, sigma2, sigma1);

            // get the coordinates of each ring
            List<Point2D> gridPoints = getGridPoint(dogImage, crossRoi, pixelSizeImage);

            // get the average x step
            double xStepAvg = getAverageStep(gridPoints.stream().map(Point2D::getX).collect(Collectors.toList()), pixelSizeImage);
            System.out.println("xStepAvg = " +xStepAvg + " pix");

            // get the average y step
            double yStepAvg = getAverageStep(gridPoints.stream().map(Point2D::getY).collect(Collectors.toList()), pixelSizeImage);
            System.out.println("yStepAvg = " +yStepAvg + " pix");

            // get the rotation angle
            double rotationAngle = getRotationAngle(gridPoints, crossRoi);
            System.out.println("theta = "+rotationAngle*180/Math.PI + "°");

            // get the ideal grid
            List<Point2D> idealGridPoints = getIdealGridPoints(crossRoi, (int)Math.sqrt(gridPoints.size() + 1), pixelSizeImage, rotationAngle);

            // display all grid points
            //double radius = (int)(0.5 * argoSpacing / pixelSizeImage);

            gridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi(pR.getX()-ovalRadius, pR.getY()-ovalRadius, 2*ovalRadius, 2*ovalRadius));});
            gridPoints.forEach(pR-> {roiManager.addRoi(new PointRoi(pR.getX(), pR.getY()));});

            // sort the computed grid points according to ideal grid order
            gridPoints = sortFromReference(Arrays.asList(roiManager.getRoisAsArray()),idealGridPoints);

            // display ideal grid points
            idealGridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi(pR.getX()-ovalRadius/2, pR.getY()-ovalRadius/2, ovalRadius, ovalRadius));});
            roiManager.runCommand(channel,"Show All without labels");

            // compute metrics
            List<Double> fieldDistortionValues = computeFieldDistortion(gridPoints, idealGridPoints);
            List<Double> fieldUniformityValues = computeFieldUniformity(gridPoints,channel,ovalRadius);
            List<Double> fwhmValues = computeFWHM(gridPoints,channel, lineLength, roiManager);

            // compute statistics on each metrics and save them in the resultsTable
            computeStatistics(fieldDistortionValues,analysisResultsRT,"field_distortion",i);
            computeStatistics(fieldUniformityValues,analysisResultsRT,"field_uniformity",i);
            computeStatistics(fwhmValues,analysisResultsRT,"fwhm",i);

            // build heat maps of each metrics
            ImagePlus fdHeatMap = computeHeatMap("field_distortion", fieldDistortionValues, (int) Math.sqrt(gridPoints.size() + 1));
            ImagePlus fuHeatMap = computeHeatMap("field_uniformity", fieldUniformityValues, (int) Math.sqrt(gridPoints.size() + 1));
            ImagePlus fwhmHeatMap = computeHeatMap("fwhm", fwhmValues, (int) Math.sqrt(gridPoints.size() + 1));

            fdHeatMap.show();
            fuHeatMap.show();
            fwhmHeatMap.show();



        }



    }

    private static Roi getCenterCross(ImagePlus imp){
        RoiManager rm = new RoiManager();

        // make sure no ROI is left on the imp
        IJ.run(imp, "Select None", "");
        IJ.run("Remove Overlay", "");

        // Detect Cross in the center of the FOV
        ImagePlus mask_imp = imp.duplicate();
        IJ.setAutoThreshold(mask_imp, thresholdingMethod);
        IJ.run(mask_imp, "Convert to Mask", "");

        IJ.run(mask_imp, "Analyze Particles...", "size="+(0.04*imp.getWidth())+"-Infinity add");

        // get central ROIs while excluding bounding semi-crosses
        List<Roi> rois = Arrays.stream(rm.getRoisAsArray()).filter(roi -> ((roi.getStatistics().xCentroid < 5 * imp.getWidth() / 8.0
                && roi.getStatistics().xCentroid > 3 * imp.getWidth() / 8.0)
                && (roi.getStatistics().yCentroid < 5 * imp.getHeight() / 8.0
                && roi.getStatistics().yCentroid > 3 * imp.getHeight() / 8.0))).collect(Collectors.toList());


        // get the ROI with larger width
        Roi crossRoi = rois.stream().max(Comparator.comparing(roi -> roi.getStatistics().roiWidth)).get();

        rm.close();
        return new Roi(crossRoi.getBounds());
    }


    private static ImagePlus DoGFilter(ImagePlus imp, double sigma1, double sigma2){
        ImagePlus impGauss1 = imp.duplicate();
        ImagePlus impGauss2 = imp.duplicate();
        IJ.run(impGauss1, "32-bit", "");
        IJ.run(impGauss2, "32-bit", "");

        IJ.run(impGauss1, "Gaussian Blur...", "sigma="+sigma1);
        IJ.run(impGauss2, "Gaussian Blur...", "sigma="+sigma2);

        return ImageCalculator.run(impGauss1, impGauss2, "Subtract create");
    }

    private static List<Point2D> getGridPoint(ImagePlus DoGImage, Roi crossRoi, double pixelSizeImage){

        // compute the number of points on each side of the center and adapte the size of the large rectangle

        int nPoints;
        Roi enlargedRectangle;

        if((DoGImage.getWidth()*pixelSizeImage < (argoFOV + 4)) && (DoGImage.getHeight()*pixelSizeImage < (argoFOV + 4))){  // 100um of field of view + 2 um on each side
            nPoints = (int)Math.min(Math.floor(((DoGImage.getWidth()*pixelSizeImage/2)-2 )/argoSpacing), Math.floor(((DoGImage.getHeight()*pixelSizeImage/2)-2)/argoSpacing));
            enlargedRectangle = RoiEnlarger.enlarge(crossRoi, (int)Math.round(((nPoints*argoSpacing+2.5)/(crossRoi.getStatistics().roiWidth*pixelSizeImage/2))*crossRoi.getStatistics().roiWidth/2));

        }
        else{
            nPoints = (argoNPoints-1)/2;
            enlargedRectangle = RoiEnlarger.enlarge(crossRoi, (int)Math.round(((nPoints*argoSpacing+1.5)/(crossRoi.getStatistics().roiWidth*pixelSizeImage/2))*crossRoi.getStatistics().roiWidth/2));
        }

       // DoGImage.setRoi(enlargedRectangle);
        double large_rect_roi_x = enlargedRectangle.getStatistics().xCentroid;
        double large_rect_roi_y = enlargedRectangle.getStatistics().yCentroid;
        double large_rect_roi_w = enlargedRectangle.getStatistics().roiWidth;
        double large_rect_roi_h = enlargedRectangle.getStatistics().roiHeight;

        IJ.run(DoGImage, "Find Maxima...", "prominence=1 output=[List]");

        // get coordinates of each point
        ResultsTable rt_points = ResultsTable.getResultsTable("Results");

        if(rt_points == null)
           return new ArrayList<>();

        float[] raw_x_array = rt_points.getColumn(0);
        float[] raw_y_array = rt_points.getColumn(1);

        List<Point2D> gridPoints = new ArrayList<>();

        // filter points according to their positon ; keep only those inside the large rectangle and oustide the small rectangle
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


    // need to have a square number of
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


    private static List<Point2D> getIdealGridPoints(Roi crossRoi, int nPoints, double pixelSize, double theta){

        double xCross = crossRoi.getStatistics().xCentroid;
        double yCross = crossRoi.getStatistics().yCentroid;

        AffineTransform at = AffineTransform.getRotateInstance(theta, xCross, yCross);

        List<Point2D> idealPoints = new ArrayList<>();

        // define the ideal grid of dots
        IntStream.range(-(nPoints-1)/2, (nPoints-1)/2+1).forEach(yP -> {
            IntStream.range(-(nPoints-1)/2, (nPoints-1)/2+1).forEach(xP -> {
                if (!( xP == 0 && yP == 0) ){ // to avoid making a point at the cross
                    double[] pt = {xCross+xP*argoSpacing/pixelSize, yCross+yP*argoSpacing/pixelSize};
                    at.transform(pt, 0, pt, 0, 1);
                    idealPoints.add(new Point2D.Double(pt[0], pt[1]));
                }
            });
        });

       return idealPoints;
    }


    private static List<Point2D> sortFromReference(List<Roi> listToSort, List<Point2D> reference){

        // here we check if "ideal" point from the grid are contained in one of the ROI of the rings,
        // so we have both list in the same order!
        List<Point2D> sorted = new ArrayList<>();

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


    private static List<Double> computeFieldDistortion(List<Point2D> gridPoints, List<Point2D> idealGridPoints){
        // Now we measure distance between ideal point and the measured one
        // compute field distortion metrics
        List<Double> distValues = new ArrayList<>();
        for(int i = 0; i < gridPoints.size(); i++){
            distValues.add(gridPoints.get(i).distance(idealGridPoints.get(i)));
        }

        return distValues;
    }

    private static List<Double> computeFieldUniformity(List<Point2D> gridPoints, ImagePlus imp, double ovalRadius){
        List<Double> intensityValues = new ArrayList<>();
        gridPoints.forEach(pt->{
            // set the ROI & make a circle
            OvalRoi ovalRoi = new OvalRoi(pt.getX()-ovalRadius, pt.getY()-ovalRadius, 2*ovalRadius, 2*ovalRadius);
            imp.setRoi(ovalRoi);
            intensityValues.add(imp.getStatistics().mean);
        });

        return intensityValues;
    }

    private static List<Double> computeFWHM(List<Point2D> gridPoints, ImagePlus imp, double lineLength, RoiManager rm){
        List<Double> fwhmValues = new ArrayList<>();
        gridPoints.forEach(pt->{
            // set the ROI & make a circle
            Line line_roi = new Line(pt.getX(), pt.getY(), pt.getX(), pt.getY() - lineLength);
            rm.addRoi(line_roi);
            // measure pixel intensitiy values using ProfilePlot ,yData, and creates an xData from calibration
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

            fwhmValues.add(fwhm_val);
        });

        return fwhmValues;
    }

    private static void computeStatistics(List<Double> values, ResultsTable rt, String metric, int channel){
        double average = values.stream().reduce(0.0, Double::sum) / values.size();
        List<Double> stdList = new ArrayList<>();
        values.forEach(e->stdList.add(Math.pow(e - average,2)));

        double std = Math.sqrt(stdList.stream().reduce(0.0, Double::sum)/values.size());
        double min = values.stream().min(Comparator.naturalOrder()).get();
        double max = values.stream().max(Comparator.naturalOrder()).get();

        rt.setValue(metric+"_avg", channel, average);
        rt.setValue(metric+"_std", channel, std);
        rt.setValue(metric+"_min", channel, min);
        rt.setValue(metric+"_max", channel, max);

        System.out.println(metric+"_avg "+average);
        System.out.println(metric+"_std "+std);
        System.out.println(metric+"_min "+min);
        System.out.println(metric+"_max "+max);
    }

    private static ImagePlus computeHeatMap(String title, List<Double> values, int nPoints){

        ImagePlus imp = IJ.createImage(title, heatMapBitDepth, nPoints, nPoints, 1);

        values.add((int)Math.floor(values.size()/2.0), Double.NaN); // here we have a O in the center, because we didn't measure anything there
        FloatProcessor fp = new FloatProcessor(nPoints, nPoints, values.stream().mapToDouble(Double::doubleValue).toArray());
        imp.getProcessor().setPixels(1, fp);

        ImagePlus enlarged_imp = imp.resize(heatMapSize, heatMapSize, "none");
        enlarged_imp.setTitle(title);
        IJ.run(enlarged_imp, "Fire", "");

        return enlarged_imp;
    }
}
