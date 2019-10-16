**Kheops** is an ImageJ plugin (IJ2 command based) to **convert image file(s) to pyramidal ome.tiff**. 

<img src="https://github.com/BIOP/ijp-kheops/images/0-kheops_logo.png" title="Kheops" width="25%" align="center">

# Kheops

##  Kheops - Convert File to Pyramidal OME

### On a single file  
 
<img src="https://github.com/BIOP/ijp-kheops/images/1-image_single_file.png" title="Kheops on Single File" width="25%" align="center">


**Select an input file (required)**
Selec the file you want to convert to a pyramidal ome.tiff 

**Specify an output folder (optional)**
By default, it will save the ome.tiff in the same fodler 

**Pyramid level(s)**
To specify how many downsampling levels of the image will be created.

**Pyramid level downsampling factor**
To specify the downsampling factor to use between levels.

**Individual Tile size (in pixel)**
To specify the size of the tiles

### On multiple files (Batch Mode) </h3> 

1.Use the //Search// tool

<img src="https://github.com/BIOP/ijp-kheops/images/2-image_fiji_main.png" title="Kheops on Single File" width="25%" align="center">


2.Search for Kheops

<img src="https://github.com/BIOP/ijp-kheops/images/3-image_multi_files.png" title="Kheops on Single File" width="25%" align="center">


3.Clic {key Batch }

4.Select images

<img src="https://github.com/BIOP/ijp-kheops/images/4-image_multi_select.png.png" title="Kheops on Single File" width="25%" align="center">


5.Clic  `Open`


## Kheops - Read Documentation ...
You arrive on our c4science page.

## Install

Please install using the [BIOP dev update site ](https://c4science.ch/w/bioimaging_and_optics_platform_biop/image-processing/imagej_tools/update-site/)


<h2> Other Sources </h2> 

[c4science repo](https://c4science.ch/source/ijp-kheops/)


## Misc.

This is an example Maven project implementing an ImageJ command with a Pom adapted for PT-BIOP at EPFL.

This project was initially cloned from https://github.com/imagej/example-imagej-command.git (11th October 2018)

