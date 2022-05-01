package ch.epfl.biop.kheops;

import bdv.viewer.SourceAndConverter;
import ch.epfl.biop.bdv.bioformats.bioformatssource.BioFormatsBdvOpener;
import ch.epfl.biop.bdv.bioformats.export.spimdata.BioFormatsConvertFilesToSpimData;
import ch.epfl.biop.bdv.bioformats.imageloader.SeriesNumber;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import ome.units.UNITS;
import ome.units.quantity.Length;
import spimdata.util.Displaysettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KheopsHelper {

    public static SourcesInfo getSourcesFromFile(String path,int tileX,
                                                            int tileY,
                                                            int maxCacheSize,
                                                            int nParallelJobs) {
        BioFormatsBdvOpener opener =
                BioFormatsConvertFilesToSpimData.getDefaultOpener(path)
                        .micrometer()
                        .queueOptions(nParallelJobs, 4)
                        .cacheBlockSize(tileX, tileY, 1)
                        .cacheBounded(maxCacheSize*nParallelJobs);

        AbstractSpimData asd = BioFormatsConvertFilesToSpimData
                .getSpimData(
                    opener.voxSizeReferenceFrameLength(new Length(1, UNITS.MILLIMETER))
                          .positionReferenceFrameLength(new Length(1,UNITS.METER)));

        Map<Integer, SourceAndConverter> idToSource = new SourceAndConverterFromSpimDataCreator(asd).getSetupIdToSourceAndConverter();

        Map<Integer, SeriesNumber> idToSeries = new HashMap<>();
        Map<Integer, String> idToNames = new HashMap<>();

        idToSource.keySet().stream()
                .forEach(id -> {
                    BasicViewSetup bvs = (BasicViewSetup) asd.getSequenceDescription().getViewSetups().get(id);
                    SeriesNumber sn = bvs.getAttribute(SeriesNumber.class);
                    if (sn!=null) {
                        idToSeries.put(id, sn);
                        idToNames.put(id, sn.getName());
                    }
                    Displaysettings displaysettings = bvs.getAttribute(Displaysettings.class);
                    if (displaysettings!=null) {
                        Displaysettings.applyDisplaysettings(idToSource.get(id), displaysettings); // Applies color to sources
                    }
                });

        int nSources = idToSource.size();

        Map<Integer, List<SourceAndConverter>> idToSacs = new HashMap<>();

        for (int id = 0; id<nSources; id++) {
            SourceAndConverter source = idToSource.get(id);
            int sn_id = idToSeries.get(id).getId();
            if (!idToSacs.containsKey(sn_id)) {
                idToSacs.put(sn_id, new ArrayList<>());
            }
            idToSacs.get(sn_id).add(source);
        }

        SourcesInfo info = new SourcesInfo();

        info.idToSources = idToSacs;
        info.idToNames = idToNames;

        return info;
    }

    public static class SourcesInfo {
        public Map<Integer, List<SourceAndConverter>> idToSources;
        public Map<Integer, String> idToNames;
    }
}
