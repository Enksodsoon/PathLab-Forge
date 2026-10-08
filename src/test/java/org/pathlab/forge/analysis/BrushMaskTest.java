package org.pathlab.forge.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.pathlab.forge.annotation.AnnotationRepository;
import org.pathlab.forge.annotation.AnnotationTransformer;

final class BrushMaskTest {
    @Test void subtractMakesRealHoleAndAddPreservesDisjointRegionAcrossReloadMeasurementsAndExport()throws Exception{
        var root=Files.createTempDirectory("brush-mask");var repository=new AnnotationRepository(root);
        var parent=repository.create("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf","rectangle","0,0;10,10","ROI","#ffaa22",0,0,0,"view");
        var hole=repository.composeBrush("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf",parent.id(),1,"brush_subtract","2,2;8,2;8,8;2,8",0,0,0,"view");
        assertEquals("roi_mask",hole.type());assertEquals(2,hole.revision());
        assertTrue(new RoiMask(hole.type(),hole.geometry()).contains(1,1));assertFalse(new RoiMask(hole.type(),hole.geometry()).contains(5,5));
        assertEquals(64,GeometryMeasurements.measure(hole.type(),hole.geometry()).get("areaPx2"),1e-9);
        var calibrated=GeometryMeasurements.measure(hole.type(),hole.geometry(),2,3);
        assertEquals(384,calibrated.get("areaUm2"),1e-9);assertEquals(160,calibrated.get("perimeterUm"),1e-9);
        var added=repository.composeBrush("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf",parent.id(),2,"brush_add","12,0;14,0;14,2;12,2",0,0,0,"view");
        var restored=new AnnotationRepository(root).list("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf").get(0);
        assertEquals(added,restored);assertEquals(1,repository.list("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf").size());
        assertTrue(new RoiMask(added.type(),added.geometry()).contains(13,1));
        var exported=AnnotationTransformer.transform(added.geometry(),0,0,20,20,2).orElseThrow();
        assertFalse(new RoiMask("roi_mask",exported).contains(2.5,2.5));assertTrue(new RoiMask("roi_mask",exported).contains(6.5,.5));
        assertThrows(IllegalStateException.class,()->repository.composeBrush("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf",parent.id(),2,"brush_add","0,0;1,0;1,1",0,0,0,"view"));
        assertThrows(IllegalArgumentException.class,()->new RoiMask("brush_subtract","0,0;1,0;1,1"));
    }
    @Test void rejectsDegenerateWrongScopeAndCancelledCompositionWithoutMutation()throws Exception{
        var repository=new AnnotationRepository(Files.createTempDirectory("brush-cancel"));
        var parent=repository.create("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf","rectangle","0,0;10,10","ROI","#ffaa22",0,0,0,"view");
        assertThrows(IllegalArgumentException.class,()->repository.composeBrush("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf",parent.id(),1,"brush_subtract","0,0;10,0;10,10;0,10",0,0,0,"view"));
        assertThrows(IllegalArgumentException.class,()->repository.composeBrush("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf",parent.id(),1,"brush_add","0,0;1,1;2,2",0,0,0,"view"));
        assertThrows(IllegalStateException.class,()->repository.composeBrush("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf",parent.id(),1,"brush_add","0,0;1,0;1,1",1,0,0,"view"));
        Thread.currentThread().interrupt();
        try{assertThrows(java.util.concurrent.CancellationException.class,()->repository.composeBrush("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf",parent.id(),1,"brush_add","0,0;1,0;1,1",0,0,0,"view"));}
        finally{Thread.interrupted();}
        assertThrows(IllegalArgumentException.class,()->repository.composeBrush("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf",parent.id(),1,"brush_subtract","12,0;14,0;14,2;12,2",0,0,0,"view"));
        assertThrows(IllegalArgumentException.class,()->MaskContours.parse("mask/2|0,0;1,0;1,1"));
        assertThrows(IllegalArgumentException.class,()->MaskContours.parse("mask/1|0,0;1,1;2,2"));
        var tooManyStrokePoints=java.util.stream.IntStream.range(0,1025).mapToObj(index->index+","+(index%2)).collect(java.util.stream.Collectors.joining(";"));
        assertDoesNotThrow(()->GeometryMeasurements.validate("freehand",tooManyStrokePoints));
        assertThrows(IllegalArgumentException.class,()->repository.composeBrush("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf",parent.id(),1,"brush_add",tooManyStrokePoints,0,0,0,"view"));
        var tooManyRings="mask/1|"+java.util.stream.IntStream.range(0,65).mapToObj(index->(index*3)+",0;"+(index*3+1)+",0;"+(index*3+1)+",1").collect(java.util.stream.Collectors.joining("|"));
        assertThrows(IllegalArgumentException.class,()->MaskContours.parse(tooManyRings));
        assertEquals(parent,repository.list("ca38d59a-08ce-44a2-aaf2-cb96bd147bdf").get(0));
    }

    @Test void curvedParentUsesStoredContoursAndCropClippingKeepsHoleGeometry()throws Exception{
        var mask=MaskContours.compose("ellipse","0,0;20,10","brush_subtract","8,3;12,3;12,7;8,7");
        assertTrue(new RoiMask("roi_mask",mask).contains(5,5));assertFalse(new RoiMask("roi_mask",mask).contains(10,5));
        assertEquals(Math.PI*50-16,GeometryMeasurements.measure("roi_mask",mask).get("areaPx2"),.2);
        var clipped=AnnotationTransformer.transform(mask,5,0,10,10,1).orElseThrow();
        assertFalse(new RoiMask("roi_mask",clipped).contains(5,5));
        assertTrue(MaskContours.parse(clipped).stream().flatMap(java.util.Collection::stream).allMatch(point->point.x()>=0&&point.x()<=10&&point.y()>=0&&point.y()<=10));
        assertTrue(AnnotationTransformer.transform(mask,50,50,10,10,1).isEmpty());
    }

    @Test void exactMaskAnalysisExcludesHoleAndSnapshotsBecomeStaleOnComposition()throws Exception{
        var root=Files.createTempDirectory("brush-analysis");var datasetId="ca38d59a-08ce-44a2-aaf2-cb96bd147bdf";
        var datasets=new org.pathlab.forge.library.PropertiesDatasetRepository(root.resolve("library.properties"));
        var source=root.resolve("synthetic.tif");Files.write(source,new byte[]{1,2,3});var inventory=org.pathlab.forge.library.DatasetSourceInventory.singleFile(source);
        var view=new org.pathlab.forge.reader.ViewDefinition(0,org.pathlab.forge.reader.AxisSelection.slice(0),org.pathlab.forge.reader.AxisSelection.slice(0),java.util.List.of(new org.pathlab.forge.reader.ChannelRender(0,true,"#ffffff",0,255)),org.pathlab.forge.reader.RenderProfile.PATHOLOGY_STANDARD);
        datasets.save(new org.pathlab.forge.library.LocalDataset(datasetId,"Synthetic",source.toString(),3,org.pathlab.forge.library.DatasetFormat.OME_TIFF,org.pathlab.forge.library.DatasetStatus.READY,"","","")
            .withSourceIdentity(org.pathlab.forge.library.DatasetStatus.READY,"",inventory.fingerprint(),inventory.serialized()).withViewDefinition(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(view),view.revision()));
        var repository=new AnnotationRepository(root);var original=repository.create(datasetId,"rectangle","0,0;8,8","ROI","#ffaa22",0,0,0,view.revision());
        var hole=repository.composeBrush(datasetId,original.id(),1,"brush_subtract","2,2;6,2;6,6;2,6",0,0,0,view.revision());
        var wait=new java.util.concurrent.atomic.AtomicBoolean();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var service=new DeterministicAnalysisService(datasets,repository,root,(id,series,z,t,x,y,w,h)->{if(wait.get()){entered.countDown();try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new java.io.IOException(error);}}var bytes=new byte[w*h*3];java.util.Arrays.fill(bytes,(byte)100);return new org.pathlab.forge.conversion.RgbRegion(x,y,w,h,bytes);},()->false,tool->true)){
            var completed=terminal(service,service.submit(new DeterministicAnalysisService.Request(datasetId,hole.id(),"he",java.util.Map.of("hematoxylinThreshold",0.0,"eosinThreshold",0.0))).id());
            assertEquals("SUCCEEDED",completed.status());assertEquals(48,completed.outputs().get("sampledPixels"));
            var bits=java.util.BitSet.valueOf(java.util.Base64.getDecoder().decode(completed.outputs().get("hematoxylinMaskBitsetBase64").toString()));assertEquals(48,bits.cardinality());assertFalse(bits.get(4*8+4));
            assertEquals("mask/1-even-odd",completed.provenance().secondaryInputs().get("roiMaskFormat"));
            repository.composeBrush(datasetId,hole.id(),2,"brush_add","10,0;12,0;12,2;10,2",0,0,0,view.revision());
            assertTrue(service.get(completed.id()).stale());assertTrue(service.exportJson(completed.id()).contains(hole.geometry()));
            wait.set(true);var pending=service.submit(new DeterministicAnalysisService.Request(datasetId,hole.id(),"he",java.util.Map.of()));assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            repository.composeBrush(datasetId,hole.id(),3,"brush_subtract","0,0;1,0;1,1;0,1",0,0,0,view.revision());release.countDown();
            var failed=terminal(service,pending.id());assertEquals("FAILED",failed.status());assertTrue(failed.outputs().isEmpty());
        }finally{release.countDown();}
    }
    private static AnalysisRun terminal(DeterministicAnalysisService service,String id)throws Exception{for(int i=0;i<300;i++){var run=service.get(id);if(java.util.Set.of("SUCCEEDED","FAILED","CANCELLED").contains(run.status()))return run;Thread.sleep(10);}throw new AssertionError("Analysis did not finish");}
}
