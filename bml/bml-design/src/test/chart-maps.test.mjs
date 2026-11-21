import assert from 'node:assert/strict';
import test from 'node:test';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../../../bml-runtime/package.json',import.meta.url));
const {build}=require('esbuild');
const bundle=await build({entryPoints:['src/main/client/helpers/chart-maps.ts'],bundle:true,write:false,format:'esm'});
const {bindChartMaps,mapNavigation,retainChartMapView}=await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`);
const boundary=(name,x)=>({type:'FeatureCollection',features:[{type:'Feature',properties:{name},geometry:{type:'Polygon',coordinates:[[[x,0],[x+1,0],[x+1,1],[x,0]]]}}]});
test('map aliases are isolated by geometry and identical definitions reuse registration',()=>{
  const option={series:[{type:'map',map:'regions',data:[{name:'West',value:7}]}],geo:{map:'regions'}};
  const west=bindChartMaps(option,{regions:boundary('West',0)});
  const east=bindChartMaps(option,{regions:boundary('East',10)});
  assert.notEqual(west.series[0].map,east.series[0].map);
  assert.equal(west.series[0].map,west.geo.map);
  assert.equal(bindChartMaps(option,{regions:boundary('West',0)}).series[0].map,west.series[0].map);
  assert.equal(option.series[0].map,'regions');
  assert.deepEqual(west.series[0].data,[{name:'West',value:7}]);
});
test('missing and malformed map definitions provide component feedback',()=>{
  for(const maps of [null,[],{}, {regions:{type:'FeatureCollection',features:{}}}, {regions:{type:'Polygon',coordinates:[]}}]) {
    assert.throws(()=>bindChartMaps({series:{type:'map',map:'regions'}},maps),/Chart maps|GeoJSON FeatureCollection/);
  }
  assert.deepEqual(bindChartMaps({series:[{type:'bar',data:[1]}]},{}),{series:[{type:'bar',data:[1]}]});
});
test('navigation honors native roam restrictions and shared geo viewports',()=>{
  assert.deepEqual(mapNavigation({series:[{type:'map',map:'regions',roam:'move'}, {type:'map',map:'regions',roam:'scale'},
    {type:'map',map:'regions',geoIndex:0,roam:true}],geo:{map:'regions',roam:true}}),[
    {kind:'series',index:0,pan:true,zoom:false},{kind:'series',index:1,pan:false,zoom:true},
    {kind:'geo',index:0,pan:true,zoom:true}]);
  assert.deepEqual(mapNavigation({series:{type:'map',map:'regions',roam:false}}),[{kind:'series',index:0,pan:false,zoom:false}]);
});
test('data replacements retain user map views without mutating declared options',()=>{
  const before={geo:{id:'world',map:'boundaries',roam:true},series:[{type:'scatter',coordinateSystem:'geo',data:[]}]};
  const next={...before,series:[{type:'scatter',coordinateSystem:'geo',data:[[12,34]]}]};
  const rendered={geo:[{id:'world',map:'boundaries',center:[10,20],zoom:3.5}]};
  const retained=retainChartMapView(next,before,rendered);
  assert.deepEqual(retained.geo,{...before.geo,center:[10,20],zoom:3.5});assert.deepEqual(retained.series,next.series);
  assert.equal(next.geo.zoom,undefined);assert.equal(before.geo.center,undefined);
});
test('intentional view changes and geometry or identity replacement reset their declared views',()=>{
  const before={series:[{id:'countries',type:'map',map:'old',zoom:1,center:[0,0]}]};
  const rendered={series:[{zoom:4,center:[20,30]}]};
  const next={series:[{...before.series[0],zoom:2,center:[1,2]}]};
  assert.deepEqual(retainChartMapView(next,before,rendered),next);
  for(const patch of [{map:'new'},{id:'other'},{type:'scatter'}]){
    const changed={series:[{...before.series[0],...patch}]};assert.deepEqual(retainChartMapView(changed,before,rendered),changed);
  }
  assert.equal(retainChartMapView(before,undefined,rendered),before);
});
test('malformed runtime view values do not replace valid declared map settings',()=>{
  const option={geo:[{map:'regions',zoom:1,center:[0,0]}]};
  for(const view of [{zoom:NaN,center:['bad',2]},{zoom:Infinity,center:[1]},{zoom:'2',center:null}]){
    assert.deepEqual(retainChartMapView(option,option,{geo:[view]}),option);
  }
});
