# Metrology with ArgoLight Slides

## Installation
- Download the latest version of our plugin. Unzip it and copy both .jar files into the **Plugins** folder of Fiji
- Activate the update site **OMERO 5.5-5.6** (On Fiji, go on `Help -> Update...`, then click on `Manage Update sites`)
- Restart Fiji

## Requirements
- You need to have access to an OMERO database where the images to analyze are located.
- Your images should be separated according to the microscope used (i.e. one dataset per microscope). All the datasets should be located under the same project.
- Images have to be named according the following structure
  - For fileset images
    - *microscopeName*\_*ArgoSlideName*\_*patternImaged*\_**d***AcquisitionDate*\_**o***Objective*\_*ImmersionMedium*.extension [*FOV*\_*Serie*]
    - Exemple : sp8int1_ArgoSGL482_b_d20230420_o20x_dry.lif [partialFoV_Image007]

  - For inividual images
    - *microscopeName*\_*ArgoSlideName*\_*patternImaged*\_**d***AcquisitionDate*\_**o***Objective*\_*ImmersionMedium*\_*FOV*\_*Serie*.extension
    - Exemple : lsm980_ArgoSGL482_b_d20230420_o20x_dry_fullFoV_Image002.czi

  - Be careful : the FOV item has to be either **fullFoV** or **partialFoV**

## User guide

### Launch the plugin

Launch the plugin by hitting `Plugins -> BIOP -> Argolight analysis tool`

*Image of the main gui*

### Basic configurations -- To do the first time you use the plugin

This step set the by-default values for input-output communication. These values are saved for your future use of the plugin ; you'll have to do it once.

1. Hit the button `General settings`. 
2. On the popup, enter the host name and port of your OMERO server
3. Optionnally, you can enter the ID of the OMERO project containing the images to analyze.
4. Enter the list of microscopes you may want to process (manually or by browsing a csv file)
In the csv file, you should have one microscope name by line.

**Be careful : the microscopes' name should match extactly the datasets name on OMERO, contained in the specified project.**

5. Optionnally, and IF you want to save results locally (see below), you can add the folder path where you want to save results.
6. Then, press OK. 


### Quick start

1. Enter your gaspar credentials
2. Enter the ID of the project that contains the microscopes datasets (if it has not been defined previously).
3. Select the microscope dataset you want to process.
4. Click OK

Every images that are not tagged with `raw` tag are processed within the selected dataset.

### Output location and settings

1. You can choose to save results on OMERO or on your local machine. 

If you choose to save results locally, you are asked to provide the parent folder (equivalent to the OMERO project). This folder may or may not contain microscope sub-folders (they are created in case they do not exist yet). This folder can be defined as default parameter by hitting the button `General settings`.

2. Checking this box will save heat maps of the computed metrics (i.e. field uniformity, field distortion, FWHM).
3. Checking this box will process ALL images within the selected dataset, without any distinction between those that have previously been processed.
4. You can choose, in case you would like to process all images, to remove all results from previous run(s).

### Processing settings

A few parameters used for segmentation and analysis can be manually set.
1. Sigma for gaussian blurring -> denoising
2. Median radius for median filtering -> denoising
3. Method to threshold the denoised image -> thresholding
4. Size of the thresholded particules to keep (> value) -> denoising
5. Analyzed area around the ring (for FWHM) -> analysis

### Live preview

A live preview mode enable you to set dynamically the processing parameters and to get direct visual feedback of the segmentation results only (the analysis results are not display).
1. Enter your gaspar credentials
2. Click on `Live preview` button
3. On the popup, add the OMERO ID of a typical image.
4. Click on `Load`. The image should display in Fiji.
5. Each time you modify one parameter, the segmentation result updates


