const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert/strict');
(async()=>{
const deps=process.env.REFERENCE_DEPS||path.resolve(__dirname,'../../compat/node_modules');const acorn=require(path.join(deps,'acorn'));
const original=path.resolve(process.argv[2]),candidate=path.resolve(process.argv[3]);
fs.writeFileSync(path.join(original,'package.json'),JSON.stringify({type:'module'}));
const before=await import(require('url').pathToFileURL(path.join(original,'js/resources/scriptlets.js')).href);
const context=vm.createContext({});vm.runInContext(fs.readFileSync(path.join(candidate,'js/scriptlets-compat.js'),'utf8'),context);
const after=context.__internetToolsScriptletResources;assert.equal(after.builtinScriptlets.length,before.builtinScriptlets.length);

let count=0;for(let i=0;i<before.builtinScriptlets.length;i++){
 const a=before.builtinScriptlets[i],b=after.builtinScriptlets[i];assert.equal(a.name,b.name);assert.deepEqual(JSON.parse(JSON.stringify(a.dependencies??null)),JSON.parse(JSON.stringify(b.dependencies??null)));
 assert.equal(a.fn.toString(),b.fn.toString(),a.name+' function body changed');count++;
}
const metadata=JSON.parse(fs.readFileSync(path.join(candidate,'internet-tools-build.json')));assert.equal(metadata.preservedInlineInjectorCount,4);
console.log('PASS: '+count+' original scriptlet function bodies byte-for-byte and dependency lists preserved in classic bundle');
})().catch(e=>{console.error(e.message);process.exitCode=1});
