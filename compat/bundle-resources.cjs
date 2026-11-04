// Acyclic ESM packaging for uBO's stringified scriptlet resources. Function bodies
// remain byte-for-byte unchanged; generic bundlers may rename their dependencies.
const fs=require('fs'),path=require('path');
module.exports=function(root,acorn){
 const modules=new Map(),active=new Set(),prefix='__it_resource_';
 const bindings=p=>p.type==='Identifier'?[p.name]:p.type==='ObjectPattern'?p.properties.flatMap(x=>bindings(x.value||x.argument)):p.type==='ArrayPattern'?p.elements.filter(Boolean).flatMap(bindings):p.type==='RestElement'?bindings(p.argument):p.type==='AssignmentPattern'?bindings(p.left):[];
 function visit(name){
  if(active.has(name))throw Error('Unexpected cycle in pinned resource graph: '+name);
  if(modules.has(name))return;
  active.add(name);const file=path.join(root,name),source=fs.readFileSync(file,'utf8');if(source.includes(prefix))throw Error('Resource namespace collision');
  const ast=acorn.parse(source,{ecmaVersion:'latest',sourceType:'module'}),edits=[],imports=[],exports=[];
  function dep(spec){if(!spec.startsWith('.'))throw Error('Unsupported resource import '+spec);const next=path.posix.normalize(path.posix.join(path.posix.dirname(name),spec));if(next.startsWith('../'))throw Error('Resource path escape');visit(next);return JSON.stringify(next);}
  for(const node of ast.body){
   if(node.type==='ImportDeclaration'){
    const target=prefix+'load('+dep(node.source.value)+')';
    if(node.specifiers.length===0)imports.push(target+';');
    else for(const item of node.specifiers){const value=item.type==='ImportNamespaceSpecifier'?target:target+'['+JSON.stringify(item.type==='ImportDefaultSpecifier'?'default':item.imported.name||item.imported.value)+']';imports.push('const '+item.local.name+'='+value+';');}
    edits.push([node.start,node.end,'']);
   }else if(node.type==='ExportNamedDeclaration'){
    if(node.declaration){edits.push([node.start,node.declaration.start,'']);const d=node.declaration;const names=d.type==='VariableDeclaration'?d.declarations.flatMap(x=>bindings(x.id)):[d.id.name];for(const n of names)exports.push([n,n]);}
    else{edits.push([node.start,node.end,'']);for(const item of node.specifiers){let expr=item.local.name;if(node.source)expr=prefix+'load('+dep(node.source.value)+')['+JSON.stringify(item.local.name)+']';exports.push([item.exported.name||item.exported.value,expr]);}}
   }else if(node.type.startsWith('Export'))throw Error('Unsupported pinned export '+node.type);
  }
  let body=source;for(const [a,b,value] of edits.sort((x,y)=>y[0]-x[0]))body=body.slice(0,a)+value+body.slice(b);
  const getters=exports.map(([n,expr])=>'Object.defineProperty('+prefix+'out,'+JSON.stringify(n)+',{enumerable:true,get:()=>'+expr+'});').join('\n');
  modules.set(name,'function('+prefix+'out){\n'+imports.join('\n')+'\n'+body+'\n'+getters+'\n}');active.delete(name);
 }
 visit('js/resources/scriptlets.js');
 return '(function(){\n"use strict";\nconst '+prefix+'cache=Object.create(null);\nconst '+prefix+'factories={\n'+[...modules].map(([name,body])=>JSON.stringify(name)+':'+body).join(',\n')+'\n};\nfunction '+prefix+'load(name){if('+prefix+'cache[name])return '+prefix+'cache[name];const out=Object.create(null);'+prefix+'cache[name]=out;'+prefix+'factories[name](out);return out;}\nglobalThis.__internetToolsScriptletResources='+prefix+'load("js/resources/scriptlets.js");\n})();\n';
};
