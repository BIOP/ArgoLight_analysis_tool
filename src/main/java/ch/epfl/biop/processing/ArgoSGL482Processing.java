package ch.epfl.biop.processing;

import ch.epfl.biop.image.ImageChannel;
import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.utils.IJLogger;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.measure.CurveFitter;
import ij.measure.ResultsTable;
import ij.plugin.RoiEnlarger;
import ij.plugin.frame.RoiManager;
import ij.process.ImageStatistics;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ArgoSGL482Processing {

    final private static int argoSpacing = 15; // um
    final private static int argoFOV = 570; // um
    final private static int argoNPoints = 39; // on each row/column
    final private static String thresholdingMethodCentralCross = "Huang dark";
    final private static String thresholdingMethodRings = "Li dark";
    public static void run(ImageFile imageFile){

        ImagePlus imp = imageFile.getImage();
        // pixel size of the image
        final double pixelSizeImage = imp.getCalibration().pixelWidth;
        // spot radius to compute the FWHM
        final int lineLength = (int)(1.25 / pixelSizeImage);
        // spot radius to compute other metrics
        final int ovalRadius = (int)(1.25 / pixelSizeImage);
        // Number of channels
        final int NChannels = imp.getNChannels();

        // add tags
        imageFile.addTags("raw", "argolight");

        // add key-values common to all channels
        imageFile.addKeyValue("Pixel_size_(um)",""+pixelSizeImage);
        imageFile.addKeyValue("Profile_length_for_FWHM_(pix)",""+lineLength);
        imageFile.addKeyValue("Oval_radius_(pix)",""+ovalRadius);
        imageFile.addKeyValue("thresholding_method_central_cross", thresholdingMethodCentralCross);
        imageFile.addKeyValue("thresholding_method_rings", thresholdingMethodRings);

        RoiManager roiManager = new RoiManager();

        for(int c = 0; c < NChannels; c++){
            // reset all windows
            IJ.run("Close All", "");
            roiManager.reset();

            ImageChannel imageChannel = new ImageChannel(c, imp.getWidth(), imp.getHeight());

            // extract the current channel
            ImagePlus channel = IJ.createHyperStack(imp.getTitle() + "_ch" + c, imp.getWidth(), imp.getHeight(), 1, 1, 1, imp.getBitDepth());
            imp.setPosition(c+1,1,1);
            channel.setProcessor(imp.getProcessor());
            channel.show();

            // get the central cross
            Roi crossRoi = getCentralCross(channel, roiManager, pixelSizeImage);
            imageChannel.setCenterCross(crossRoi);
            IJLogger.info("Channel "+c,"Cross = " +crossRoi);

            // add the cross ROI on the image
            roiManager.addRoi(crossRoi);
            channel.setRoi(crossRoi);

            double sigma = 0.2 / pixelSizeImage;
            List<Point2D> gridPoints = getGridPoint(channel, crossRoi, pixelSizeImage, sigma);
            imageChannel.addKeyValue("Sigma",""+sigma);

            // display all points (grid and ideal)
            roiManager.reset();

            if(imageFile.getZoomFactor() <= 1){
                // reduced grid to compute average step
                List<Point2D> smallerGrid = gridPoints.stream()
                        .filter(e -> (Math.abs(e.getX() - crossRoi.getStatistics().xCentroid) < (2.5*argoSpacing) / pixelSizeImage && Math.abs(e.getY() - crossRoi.getStatistics().yCentroid) < (2.5*argoSpacing) / pixelSizeImage))
                        .collect(Collectors.toList());

                // get the average x step
                double xStepAvg = getAverageStep(smallerGrid.stream().map(Point2D::getX).collect(Collectors.toList()), pixelSizeImage);
                imageChannel.addKeyValue("ch"+c+"_xStepAvg_(pix)",""+xStepAvg);
                IJLogger.info("Channel "+c,"xStepAvg = " +xStepAvg + " pix");

                // get the average y step
                double yStepAvg = getAverageStep(smallerGrid.stream().map(Point2D::getY).collect(Collectors.toList()), pixelSizeImage);
                imageChannel.addKeyValue("ch"+c+"_yStepAvg_(pix)",""+yStepAvg);
                IJLogger.info("Channel "+c,"yStepAvg = " +yStepAvg + " pix");

                // get the rotation angle
                double rotationAngle = getRotationAngle(gridPoints, crossRoi);
                imageChannel.setRotationAngle(rotationAngle*180/Math.PI);
                IJLogger.info("Channel "+c,"Rotation angle theta = "+rotationAngle*180/Math.PI + "°");

                // create grid point ROIs
                gridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi((pR.getX()-4*ovalRadius+0.5), pR.getY()-4*ovalRadius+0.5, 8*ovalRadius, 8*ovalRadius));});

                // get the ideal grid
                List<Point2D> idealGridPoints = getIdealGridPoints(crossRoi, (int)Math.sqrt(gridPoints.size() + 1), xStepAvg, yStepAvg, rotationAngle);

                // sort the computed grid points according to ideal grid order
                gridPoints = sortFromReference(Arrays.asList(roiManager.getRoisAsArray()), idealGridPoints);

                // display all points (grid and ideal)
                roiManager.reset();
                gridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi((pR.getX()-ovalRadius+0.5), pR.getY()-ovalRadius+0.5, 2*ovalRadius, 2*ovalRadius));});
                imageChannel.addGridRings(Arrays.asList(roiManager.getRoisAsArray()));

                List<Roi> idealGridPointsRoi = new ArrayList<>();
                idealGridPoints.forEach(pR-> {idealGridPointsRoi.add(new OvalRoi(pR.getX()-ovalRadius/2 +0.5, pR.getY()-ovalRadius/2 +0.5, ovalRadius, ovalRadius));});
                idealGridPointsRoi.forEach(roiManager::addRoi);
                imageChannel.addIdealRings(idealGridPointsRoi);

                // compute metrics
                imageChannel.addFieldDistortion(computeFieldDistortion(gridPoints, idealGridPoints, pixelSizeImage));
                imageChannel.addFieldUniformity(computeFieldUniformity(gridPoints,channel,ovalRadius));

            }else {
                // create grid point ROIs
                gridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi((pR.getX()-ovalRadius+0.5), pR.getY()-ovalRadius+0.5, 2*ovalRadius, 2*ovalRadius));});
                // save ROIs
                imageChannel.addGridRings(Arrays.asList(roiManager.getRoisAsArray()));
                // add grid point centers
                gridPoints.forEach(pR -> {roiManager.addRoi(new PointRoi(pR.getX(), pR.getY()));});
                // compute metrics
                imageChannel.addFWHM(computeFWHM(gridPoints,channel, lineLength, roiManager, pixelSizeImage));
            }
            roiManager.runCommand(channel,"Show All without labels");
            imageFile.addChannel(imageChannel);
        }
        roiManager.reset();
        roiManager.close();
        IJ.run("Close All", "");

        if(NChannels > 1)
            imageFile.computePCC();
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
        IJ.setAutoThreshold(mask_imp, thresholdingMethodCentralCross);
        IJ.run(mask_imp, "Convert to Mask", "");
        IJ.run(mask_imp, "Analyze Particles...", "size="+(2.5/imagePixelSize)+"-Infinity add");

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
     * generate a list of point with small rings coordinates.
     *
     * @param DoGImage
     * @param crossRoi
     * @param pixelSizeImage
     * @return
     */
    private static List<Point2D> getGridPoint(ImagePlus DoGImage, Roi crossRoi, double pixelSizeImage,double sigma){
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
        ImagePlus imp2 = DoGImage.duplicate();
        // preprocess the image
        IJ.run(imp2, "Median...", "radius="+sigma);
        IJ.run(imp2, "Gaussian Blur...", "sigma="+sigma);

        // threshold the image
        //double medianValue = imp2.getStatistics().median;
        //IJ.setThreshold(imp2, medianValue+1, 255);
        IJ.setAutoThreshold(imp2, thresholdingMethodRings);
        IJ.run(imp2, "Convert to Mask", "");

        // make measurements
        IJ.run("Set Measurements...", "area mean min centroid center perimeter display redirect=None decimal=3");
        IJ.run(imp2, "Analyze Particles...", "pixel display clear overlay add");
        ResultsTable rt_points = ResultsTable.getResultsTable("Results");
        RoiManager rm = RoiManager.getRoiManager();
        rt_points.reset();
        rm.runCommand(DoGImage,"Measure");

        // get coordinates of each point
        float[] raw_x_array = rt_points.getColumn(rt_points.getColumnIndex("XM"));
        float[] raw_y_array = rt_points.getColumn(rt_points.getColumnIndex("YM"));

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
                    ImageStatistics stat = gPt.getStatistics();
                    sorted.add(new Point2D.Double(stat.xCentroid, stat.yCentroid));
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
    private static List<Double> computeFWHM(List<Point2D> gridPoints, ImagePlus imp, int lineLength, RoiManager rm, double pixelSize){
        List<Double> fwhmValues = new ArrayList<>();

        double[] xData = new double[lineLength];
        for(int i = 0; i < lineLength; i++) xData[i] = i;//*pixelSize;

        // for each ring
        for(Point2D pt : gridPoints){
            int nAngles = 30;
            double[] fwhmRingValues = new double[nAngles];

            for(int angle = 0; angle < nAngles; angle++){
                double angleRad = angle * Math.PI / nAngles;
                double[] avgProfile = new double[lineLength];
                for(int i = 0; i < lineLength; i++) {
                    avgProfile[i] = imp.getProcessor().getInterpolatedValue(pt.getX() + i*Math.cos(angleRad), pt.getY() + i*Math.sin(angleRad));
                }

                // DO the curve fitting
                CurveFitter crvFitr = new CurveFitter(xData, avgProfile);
                crvFitr.doFit(CurveFitter.GAUSSIAN);
                double[] params = crvFitr.getParams();

                // Fit parameters as follows: y = a + (b - a) exp( -(x - c^2)/(2*d^2) )
                double d = params[3]; // parameter d of gaussian, related to the FWHM, see http://fr.wikipedia.org/wiki/Largeur_%C3%A0_mi-hauteur
                double fwhm_val = (2 * Math.sqrt(2 * Math.log(2))) * d;

                fwhmRingValues[angle] = fwhm_val*pixelSize;
            }

            Arrays.sort(fwhmRingValues);
            int q1Pos = (int) (fwhmRingValues.length * 0.25);
            int q3Pos = (int) (fwhmRingValues.length * 0.75);
            double avgFWHM = 0;

            for(int i = q1Pos; i <= q3Pos; i++)
                avgFWHM += fwhmRingValues[i];

            fwhmValues.add(avgFWHM/(q3Pos-q1Pos+1));
        }

        return fwhmValues;
    }
}
