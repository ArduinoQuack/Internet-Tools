// uBO stringifies inline injector functions. Preserve their source text before
// bundling; formatting or renaming inside those strings changes injection code.
const fs=require('fs'),path=require('path');
module.exports=(root,acorn,records)=>({name:'preserve-ubo-injectors',setup(build){
 build.onLoad({filter:/\.js$/},args=>{
  if(!args.path.startsWith(root+path.sep))return;
  const source=fs.readFileSync(args.path,'utf8'),ast=acorn.parse(source,{ecmaVersion:'latest',sourceType:'module'}),edits=[];
  function visit(node){
   if(!node||typeof node!=='object')return;
   if(node.type==='CallExpression'&&node.arguments.length===0&&node.callee.type==='MemberExpression'&&!node.callee.computed&&node.callee.property.name==='toString'){
    const receiver=node.callee.object;if(['FunctionExpression','ArrowFunctionExpression'].includes(receiver.type)){
     const text=source.slice(receiver.start,receiver.end);edits.push([node.start,node.end,JSON.stringify(text)]);records.push({file:path.relative(root,args.path),text});return;
    }
   }
   for(const value of Object.values(node))if(Array.isArray(value))value.forEach(visit);else if(value&&typeof value==='object')visit(value);
  }
  visit(ast);if(!edits.length)return;
  let contents=source;for(const [a,b,text] of edits.sort((x,y)=>y[0]-x[0]))contents=contents.slice(0,a)+text+contents.slice(b);
  return {contents,loader:'js',resolveDir:path.dirname(args.path)};
 });
}});
