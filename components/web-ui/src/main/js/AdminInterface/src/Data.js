import React from 'react';
import './Data.css';
import uuid from 'uuid';
const Data = (props) =>{
  

  const zx = props.current.map(  (appName) => {
    var Active = props.num[appName].activeOrigins
    var Inactive = props.num[appName].inactiveOrigins
    var disabled = props.num[appName].disabledOrigins
   
    var activeMap =Active.map((origin)=><table><tbody><tr className='active'><td key={uuid()}>{origin.id}</td><td key={uuid()}>{origin.host}</td><td>Active</td><button onClick={(e)=>{props.dis(appName, origin.id)}}>Disable</button></tr></tbody></table>) 
    var inactiveMap = Inactive.map((origin)=><table><tbody><tr className='inactive'><td key={uuid()}>{origin.id}</td><td key={uuid()}>{origin.host}</td><td>Inactive</td><button onClick={(e)=>{props.dis(appName, origin.id)}}>Disable</button></tr></tbody></table>)
    var disableMap = disabled.map((origin)=><table><tbody><tr className='disable'><td key={uuid()}>{origin.id}</td><td key={uuid()}>{origin.host}</td><td>Disabled</td><button onClick={(e)=>{props.enable(appName, origin.id)}}>Enable</button></tr></tbody></table>)
        
    var displayMap = [];

    if (props.filter.active) {
      displayMap = displayMap.concat(activeMap)
    }
    if (props.filter.inactive) {
      displayMap = displayMap.concat(inactiveMap)
    }
    if (props.filter.disable) {
      displayMap = displayMap.concat(disableMap)
    }
        
    return <fieldset><legend>{appName}</legend> {displayMap}</fieldset>
                             

    }  )
      

    return (  
    
    <div className='data'>
         {zx} 
    </div>
  )  
}
  export default Data;

