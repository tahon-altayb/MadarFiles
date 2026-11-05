// Existing execution suites accept the now-visible default option cards as a user.
// Dedicated conversation tests exercise changing, cancelling and restoring them.
exports.acceptChoices=async page=>{
 for(let n=0;n<3;n++){
  const button=page.locator('.agentChoiceActions .primary');if(!await button.count()||!await button.isVisible())return;
  await button.click();
 }
};
exports.savedVoice=async(page,value)=>page.evaluate(value=>{const el=document.getElementById('agentMotionVoice');el.value=value;el.dispatchEvent(new Event('change',{bubbles:true}));},value);
