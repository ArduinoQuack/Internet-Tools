// Samsung 30.0.5.27 terminates the extension renderer on ChromeSetting writes.
// Expose uBO's existing unsupported-platform behavior instead of sending those IPCs.
module.exports = function samsungCapabilities(source) {
    const start=source.indexOf('// browser.privacy entries');
    const end=source.indexOf('// https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/storage/managed',start);
    if(start<0||end<start)throw Error('Pinned privacy wrapper section did not match');
    source=source.slice(0,start)+`// ChromeSetting is unsupported by the targeted Samsung native implementation.
// Do not access chrome.privacy or send get/set/clear IPCs. uBO disables the three
// browser-setting controls when this capability is undefined; filtering remains available.
webext.privacy = undefined;

`+source.slice(end);
    return source;
};
