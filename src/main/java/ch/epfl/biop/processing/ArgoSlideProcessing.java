package ch.epfl.biop.processing;

import ch.epfl.biop.image.ImageChannel;
import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.utils.IJLogger;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This class runs a processing on ArgoLight slide images.
 * The protocol has to implement the following rules
 * <p>
 * <ul>
 * <li> The image should be centered on the grid pattern (pattern B) </li>
 * <li> The ArgoSlide should be designed for low-magnification objectives </li>
 * <li> The dynamic range should be as large as possible, but without any saturation </li>
 * <li> The rotation angle should be minimized </li>
 * <li> The image name must follow a specific pattern : </li>
 *      <p>
 *      <ul>
 *      <li> MicroscopeName_ArgoSlideName_pattern_dDate_oObjective_immersion_FoV_serie.extension for single file
 *      (ex: lsm980_ArgoSLG482_b_d20230405_o63x_oil_fullFoV_1.czi) </li>
 *      <li> MicroscopeName_ArgoSlideName_pattern_dDate_oObjective_immersion.extension [FoV_serie] for fileset images
 *      (ex: sp8up1_ArgoSLG482_b_d20230405_o63x_oil.lif [fullFoV_1] </li>
 *      </ul>
 *      <p>
 * </ul>
 * <p>
 *
 * Two different images are allowed with this protocol
 * <p>
 * <ul>
 * <li> One image where the grid covers the entire field of view of the camera.
 *  Field distortion and field uniformity are computed from this image </li>
 * <li> One image with only 2 rows / columns of rings around the central cross.
 * Full Width at Half Maximum is computed from this image.</li>
 * </ul>
 * <p>
 */
public class ArgoSlideProcessing {

    final private static int argoSpacing = 15; // um
    final private static int argoFOV = 570; // um
    final private static int argoNPoints = 39; // on each row/column

    /**
     * Run the analysis on the current image.
     *
     * @param imageFile image to process
     * @param userSigma value of sigma for gaussian blurring
     * @param userMedianRadius value of median radius for median filtering
     * @param userThresholdingMethod thresholding method used
     * @param userParticleThreshold value of the threshold on particle size
     * @param userRingRadius value of the analysis circle radius around each ring
     */
    public static void run(ImageFile imageFile, double userSigma, double userMedianRadius, String userThresholdingMethod,
                           double userParticleThreshold, double userRingRadius){

        final ImagePlus imp = imageFile.getImage();
        // pixel size of the image
        final double pixelSizeImage = imp.getCalibration().pixelWidth;
        // spot radius to compute the FWHM
        final int lineLength = (int)(userRingRadius / pixelSizeImage);
        // spot radius to compute other metrics
        final int ovalRadius = lineLength;
        // Number of channels
        final int NChannels = imp.getNChannels();
        // sigma for gaussian blurring
        final double sigma = userSigma / pixelSizeImage;
        // median radius for median filtering
        final double medianRadius = userMedianRadius / pixelSizeImage;
        // threshold on the size of particles to filter
        final double particleThreshold = userParticleThreshold / pixelSizeImage;

        // add tags
        imageFile.addTags("raw", "argolight");

        // add key-values common to all channels
        imageFile.addKeyValue("Pixel_size_(um)",""+pixelSizeImage);
        imageFile.addKeyValue("Profile_length_for_FWHM_(pix)",""+lineLength);
        imageFile.addKeyValue("Oval_radius_(pix)",""+ovalRadius);
        imageFile.addKeyValue("Thresholding_method", userThresholdingMethod);
        imageFile.addKeyValue("Sigma_(pix)",""+sigma);
        imageFile.addKeyValue("Median_radius_(pix)",""+medianRadius);
        imageFile.addKeyValue("Particle_threshold",""+particleThreshold);

        IJLogger.info("Image","Pixel size : "+pixelSizeImage+ " um");
        IJLogger.info("Detection parameters","Ring radius : "+ovalRadius + " pix");
        IJLogger.info("Detection parameters","Sigma : "+sigma + " pix");
        IJLogger.info("Detection parameters","Median radius : "+medianRadius + " pix");
        IJLogger.info("Detection parameters","Particle threshold : "+particleThreshold + " pix");

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
            Roi crossRoi = Processing.getCentralCross(channel, roiManager, pixelSizeImage, userThresholdingMethod, argoFOV);
            imageChannel.setCenterCross(crossRoi);
            IJLogger.info("Channel "+c,"Cross = " +crossRoi);

            // add the cross ROI on the image
            roiManager.addRoi(crossRoi);
            channel.setRoi(crossRoi);

            List<Point2D> gridPoints = Processing.getGridPoint(channel, crossRoi, pixelSizeImage, sigma, medianRadius,
                    particleThreshold, userThresholdingMethod, argoFOV, argoSpacing, argoNPoints);

            // display all points (grid and ideal)
            roiManager.reset();

            if(imageFile.getImagedFoV().equals("fullFoV")){
                // reduced grid to compute average step
                List<Point2D> smallerGrid = gridPoints.stream()
                        .filter(e -> (Math.abs(e.getX() - crossRoi.getStatistics().xCentroid) < (2.5*argoSpacing) / pixelSizeImage && Math.abs(e.getY() - crossRoi.getStatistics().yCentroid) < (2.5*argoSpacing) / pixelSizeImage))
                        .collect(Collectors.toList());

                // get the average x step
                double xStepAvg = Processing.getAverageStep(smallerGrid.stream().map(Point2D::getX).collect(Collectors.toList()), pixelSizeImage, argoSpacing);
                imageChannel.addKeyValue("ch"+c+"_xStepAvg_(pix)",""+xStepAvg);
                IJLogger.info("Channel "+c,"xStepAvg = " +xStepAvg + " pix");

                // get the average y step
                double yStepAvg = Processing.getAverageStep(smallerGrid.stream().map(Point2D::getY).collect(Collectors.toList()), pixelSizeImage, argoSpacing);
                imageChannel.addKeyValue("ch"+c+"_yStepAvg_(pix)",""+yStepAvg);
                IJLogger.info("Channel "+c,"yStepAvg = " +yStepAvg + " pix");

                // get the rotation angle
                double rotationAngle = Processing.getRotationAngle(gridPoints, crossRoi);
                imageChannel.setRotationAngle(rotationAngle*180/Math.PI);
                IJLogger.info("Channel "+c,"Rotation angle theta = "+rotationAngle*180/Math.PI + "°");

                // create grid point ROIs
                gridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi((pR.getX()-4*ovalRadius+0.5), pR.getY()-4*ovalRadius+0.5, 8*ovalRadius, 8*ovalRadius));});

                // get the ideal grid
                List<Point2D> idealGridPoints = Processing.getIdealGridPoints(crossRoi, (int)Math.sqrt(gridPoints.size() + 1), xStepAvg, yStepAvg, rotationAngle);

                // sort the computed grid points according to ideal grid order
                gridPoints = Processing.sortFromReference(Arrays.asList(roiManager.getRoisAsArray()), idealGridPoints);

                // display all points (grid and ideal)
                roiManager.reset();
                gridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi((pR.getX()-ovalRadius+0.5), pR.getY()-ovalRadius+0.5, 2*ovalRadius, 2*ovalRadius));});
                imageChannel.addGridRings(Arrays.asList(roiManager.getRoisAsArray()));

                List<Roi> idealGridPointsRoi = new ArrayList<>();
                idealGridPoints.forEach(pR-> {idealGridPointsRoi.add(new OvalRoi(pR.getX()-ovalRadius/2 +0.5, pR.getY()-ovalRadius/2 +0.5, ovalRadius, ovalRadius));});
                idealGridPointsRoi.forEach(roiManager::addRoi);
                imageChannel.addIdealRings(idealGridPointsRoi);

                // compute metrics
                imageChannel.addFieldDistortion(Processing.computeFieldDistortion(gridPoints, idealGridPoints, pixelSizeImage));
                imageChannel.addFieldUniformity(Processing.computeFieldUniformity(gridPoints,channel,ovalRadius));

            }else {
                // create grid point ROIs
                gridPoints.forEach(pR-> {roiManager.addRoi(new OvalRoi((pR.getX()-ovalRadius+0.5), pR.getY()-ovalRadius+0.5, 2*ovalRadius, 2*ovalRadius));});
                // save ROIs
                imageChannel.addGridRings(Arrays.asList(roiManager.getRoisAsArray()));
                // add grid point centers
                gridPoints.forEach(pR -> {roiManager.addRoi(new PointRoi(pR.getX(), pR.getY()));});
                // compute metrics
                imageChannel.addFWHM(Processing.computeFWHM(gridPoints,channel, lineLength, pixelSizeImage));
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
}
