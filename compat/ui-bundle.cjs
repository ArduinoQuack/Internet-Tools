/* Retain real uBO pages; compile their ES modules to deferred classic bundles. */
const fs=require('fs'),path=require('path');
module.exports=async(work,esbuild)=>{
 const output=[];
 for(const filename of fs.readdirSync(work).filter(n=>n.endsWith('.html'))){
  const file=path.join(work,filename);let html=fs.readFileSync(file,'utf8');const modules=[];
  html=html.replace(/<script\s+([^>]*?)><\/script>/g,(tag,attrs)=>{
   if(!/\btype=["']module["']/.test(attrs))return tag;
   const src=attrs.match(/\bsrc=["']([^"']+)["']/)?.[1];if(!src)throw Error('Unrecognized module '+filename);
   modules.push(src.replace(/^\//,''));return '';
  });
  if(!modules.length)continue;
  const out='js/ui-'+filename.replace(/\.html$/,'')+'.js';
  await esbuild.build({stdin:{contents:modules.map(m=>'import '+JSON.stringify('./'+m)+';').join('\n'),resolveDir:work,sourcefile:'ui-entry-'+filename+'.js'},outfile:path.join(work,out),bundle:true,format:'iife',target:'chrome130',logLevel:'warning'});
  html=html.replace('</body>','<script src="'+out+'" defer data-internet-tools-ui="'+filename+'"></script>\n</body>');
  fs.writeFileSync(file,html);output.push({page:filename,bundle:out,modules});
 }
 return output;
};
