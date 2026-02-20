EntityEvents.hurt(e =>{
    let s = e.getSource();
    let et = e.getEntity();
    let p = s.getActual();
    let i = p.getMainHandItem();
    let hid = p.getMainHandItem().getId();
    let exampleid = "apple";
    if (s && et.isLiving() && hi == exampleid){
        et.setHeath(0);
        et.setMaxHealth(0);
        i.damageVaule--;
    }
})